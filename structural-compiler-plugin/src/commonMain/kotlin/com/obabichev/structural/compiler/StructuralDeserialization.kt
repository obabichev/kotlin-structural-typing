@file:OptIn(SessionConfiguration::class)

package com.obabichev.structural.compiler

import com.obabichev.structural.runtime.ClasspathShapeResolver
import com.obabichev.structural.runtime.StructuralIndex
import com.obabichev.structural.runtime.StructuralMatches
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.SessionConfiguration
import org.jetbrains.kotlin.fir.declarations.builder.FirRegularClassBuilder
import org.jetbrains.kotlin.fir.deserialization.FirDeserializationExtension
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.extensions.FirTypeAttributeExtension
import org.jetbrains.kotlin.fir.types.ConeAttribute
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.toLookupTag
import org.jetbrains.kotlin.name.ClassId

/**
 * Gives a class from a dependency its `@Structural` interfaces as the compiler reads it, so that `size(rect)` compiles
 * for a class whose class file never mentioned `Sized`.
 *
 * The plugin can't add a supertype to a dependency class the way it does for source classes: supertype generation runs
 * only over source declarations. Deserialization is the other end. When the compiler turns a `.class` file into a
 * `FirRegularClass` it calls [FirDeserializationExtension.configureDeserializedClass] with the builder in hand, just
 * after filling in `superTypeRefs` — which is how the compiler itself gives deserialized classes `java.io.Serializable`
 * (`FirJvmDeserializationExtension.addSerializableIfNeeded`). Adding the supertype there, before the class exists, also
 * avoids the caches that would already be stale by the time the class could be mutated: `FirCorrespondingSupertypesCache`
 * and the use-site scopes in `ScopeSession` are computed on first use and never invalidated.
 *
 * Matching is decided entirely from class file bytes by `structural-runtime` — the same code the classloader uses — so
 * what the compiler believes here and what happens at run time cannot drift apart. It also sidesteps an ordering trap:
 * a dependency class can be deserialized long before a source `@Structural` interface has resolved members, so only
 * interfaces that are themselves on the classpath take part.
 */
internal class StructuralDeserializationExtension(
    session: FirSession,
    private val matches: StructuralMatches,
) : FirDeserializationExtension(session) {

    override fun FirRegularClassBuilder.configureDeserializedClass(classId: ClassId) {
        for (iface in matches.interfacesOf(classId.internalName())) {
            superTypeRefs += buildResolvedTypeRef {
                coneType = ConeClassLikeTypeImpl(
                    ClassId.fromString(iface).toLookupTag(),
                    emptyArray(),
                    isMarkedNullable = false,
                )
            }
        }
    }
}

/**
 * Registers [StructuralDeserializationExtension] on the library session, which is the session that deserializes.
 *
 * This is the unsupported part, and it is deliberate. `FirDeserializationExtension` is not one of the extensions a
 * plugin may register: it is missing from `FirExtensionRegistrar.AVAILABLE_EXTENSIONS`, and the library session keeps
 * only `ALLOWED_EXTENSIONS_FOR_LIBRARY_SESSION` — `FirTypeAttributeExtension` and `FirFunctionTypeKindExtension`. So
 * the plugin registers a type attribute extension that contributes no attributes, purely to be handed the library
 * session early enough, and registers the real extension from there. Session components compose, so the JVM's own
 * deserialization extension keeps working.
 *
 * The supported fix is upstream and small: put `FirDeserializationExtension` in both lists and route it in
 * `registerExtensions`. Until then this depends on the extension factory running before the symbol providers are
 * built, which it does today but which upstream is free to change.
 */
internal class StructuralLibrarySessionHook(
    session: FirSession,
    private val classpath: List<File>,
) : FirTypeAttributeExtension(session) {

    init {
        if (session.kind == FirSession.Kind.Library) {
            session.register(StructuralDeserializationExtension(session, matchesFor(classpath)))
        }
    }

    override fun extractAttributeFromAnnotation(annotation: FirAnnotation): ConeAttribute<*>? = null

    override fun convertAttributeToAnnotation(attribute: ConeAttribute<*>): FirAnnotation? = null
}

/** `com/example/Outer.Inner` as the JVM writes it, which is what the matcher keys on. */
private fun ClassId.internalName(): String = asString().replace('.', '$')

/**
 * Scanning a classpath for `@Structural` interfaces costs a pass over its class files, and the same classpath is used
 * by every session of a compilation, so the result is kept.
 */
private val matchesByClasspath = ConcurrentHashMap<List<File>, StructuralMatches>()

private fun matchesFor(classpath: List<File>): StructuralMatches = matchesByClasspath.getOrPut(classpath) {
    StructuralMatches(
        index = StructuralIndex.fromClasspath(ClasspathShapeResolver.urlsOf(classpath)),
        resolver = ClasspathShapeResolver(classpath),
    )
}
