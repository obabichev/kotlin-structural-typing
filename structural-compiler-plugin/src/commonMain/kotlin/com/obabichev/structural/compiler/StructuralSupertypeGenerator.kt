package com.obabichev.structural.compiler

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructClassLikeType

/** Adds every @Structural interface that a class matches as a supertype of that class. */
class StructuralSupertypeGenerator(session: FirSession) : FirSupertypeGenerationExtension(session) {
    private val fileScopes = FileScopes(session)
    private val structuralInterfaces by lazy { session.structuralInterfaces(SupertypePhaseTypes(session, fileScopes)) }

    override fun FirDeclarationPredicateRegistrar.registerPredicates() {
        register(STRUCTURAL_PREDICATE)
    }

    override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean =
        declaration is FirRegularClass && declaration.classKind in MATCHABLE_CLASS_KINDS

    override fun computeAdditionalSupertypes(
        classLikeDeclaration: FirClassLikeDeclaration,
        resolvedSupertypes: List<FirResolvedTypeRef>,
        typeResolver: TypeResolveService,
    ): List<ConeKotlinType> {
        val klass = classLikeDeclaration as? FirRegularClass ?: return emptyList()
        val types = SupertypePhaseTypes(session, fileScopes, klass.symbol, typeResolver)
        val existing = resolvedSupertypes.mapNotNull { it.coneType.classId }.toSet()
        val members = session.classMembers(klass.symbol, resolvedSupertypes.map { it.coneType }, types)
        val added = structuralInterfaces
            .filter { it.classId !in existing && session.implementsByShape(members, it, types) }
            .map { it.classId.constructClassLikeType(emptyArray(), isMarkedNullable = false) }

        if (klass.classKind == ClassKind.ENUM_CLASS && added.isNotEmpty()) {
            // The command-line compiler only writes computed supertypes back to classes that started with an unresolved
            // or implicit supertype (FirApplySupertypesTransformer.applyResolvedSupertypesToClass, Kotlin 2.4.20). An enum
            // class starts with an already resolved `Enum<E>`, so returned supertypes would be dropped there: add them to
            // the class directly as well. IntelliJ (LLFirSuperTypeTargetResolver) always replaces the supertypes with the
            // computed ones, so they must also be returned.
            val present = klass.superTypeRefs.mapNotNull { (it as? FirResolvedTypeRef)?.coneType?.classId }.toSet()
            val source = klass.source?.pluginGenerated()
            val missing = added.filter { it.classId !in present }
            if (missing.isNotEmpty()) {
                klass.replaceSuperTypeRefs(klass.superTypeRefs + missing.map { buildResolvedTypeRef { coneType = it; this.source = source } })
            }
        }
        return added
    }
}
