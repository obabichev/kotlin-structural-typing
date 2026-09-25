package com.obabichev.structural.runtime

import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.KmType
import kotlin.metadata.KmValueParameter
import kotlin.metadata.Modality
import kotlin.metadata.Visibility
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.isConst
import kotlin.metadata.isInline
import kotlin.metadata.isNullable
import kotlin.metadata.isSuspend
import kotlin.metadata.isVar
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.modality
import kotlin.metadata.visibility

/**
 * A property or function of a class or interface, seen at the level the matching rules need: the JVM method that
 * implements it, plus the Kotlin facts only `@Metadata` knows — `val` versus `var`, nullability, parameter names,
 * `suspend`. Java members have no metadata and carry only their JVM signature.
 */
internal class MemberView(
    val jvmName: String,
    val jvmDescriptor: String,
    val kotlinName: String?,
    val isProperty: Boolean,
    val isVar: Boolean = false,
    val isConst: Boolean = false,
    val isInline: Boolean = false,
    val isSuspend: Boolean = false,
    val isPublic: Boolean = true,
    val isAbstract: Boolean = false,
    val hasReceiver: Boolean = false,
    val isGeneric: Boolean = false,
    val returnType: KmType? = null,
    val parameters: List<KmValueParameter>? = null,
    val setterName: String? = null,
    val setterDescriptor: String? = null,
    val setterPublic: Boolean = false,
) {
    val returnDescriptor: String get() = jvmDescriptor.substringAfter(')')
    val parameterDescriptor: String get() = jvmDescriptor.substringAfter('(').substringBefore(')')
}

/**
 * The members of [shape] as the matcher sees them. Kotlin classes are read from their metadata, which gives the
 * authoritative JVM signature of every accessor — so `@JvmName` and the `isReady`/`setReady` naming rules come out
 * right rather than being guessed. Java classes fall back to their declared methods.
 *
 * Fields are never members: the compiler plugin collects only properties and functions, so a Java `public int width`
 * does not satisfy `val width: Int` and only `getWidth()` does.
 */
internal fun declaredMembers(shape: ClassShape): List<MemberView> {
    val km = shape.kotlin ?: return javaMembers(shape)
    val members = mutableListOf<MemberView>()

    for (property in km.properties) {
        val getter = property.getterSignature ?: continue
        members += property.toMemberView(getter.name, getter.descriptor)
    }
    for (function in km.functions) {
        val signature = function.signature ?: continue
        members += function.toMemberView(signature.name, signature.descriptor)
    }
    // An enum's built-in `name` has no accessor of its own: it is java.lang.Enum.name(). Expose it under the name a
    // `val name: String` requirement asks for, so the same requirement matches; the transformer emits the bridge.
    if (shape.isEnum) {
        members += MemberView(
            jvmName = ENUM_NAME_METHOD,
            jvmDescriptor = "()Ljava/lang/String;",
            kotlinName = "name",
            isProperty = true,
        )
    }
    return members
}

private fun KmProperty.toMemberView(jvmName: String, descriptor: String) = MemberView(
    jvmName = jvmName,
    jvmDescriptor = descriptor,
    kotlinName = name,
    isProperty = true,
    isVar = isVar,
    isConst = isConst,
    isPublic = visibility == Visibility.PUBLIC,
    isAbstract = modality == Modality.ABSTRACT,
    hasReceiver = receiverParameterType != null,
    isGeneric = typeParameters.isNotEmpty(),
    returnType = returnType,
    setterName = setterSignature?.name,
    setterDescriptor = setterSignature?.descriptor,
    setterPublic = setter?.visibility?.let { it == Visibility.PUBLIC } ?: (visibility == Visibility.PUBLIC),
)

private fun KmFunction.toMemberView(jvmName: String, descriptor: String) = MemberView(
    jvmName = jvmName,
    jvmDescriptor = descriptor,
    kotlinName = name,
    isProperty = false,
    isInline = isInline,
    isSuspend = isSuspend,
    isPublic = visibility == Visibility.PUBLIC,
    isAbstract = modality == Modality.ABSTRACT,
    hasReceiver = receiverParameterType != null,
    isGeneric = typeParameters.isNotEmpty(),
    returnType = returnType,
    parameters = valueParameters,
)

/** Java classes have no metadata: their public instance methods are the members, compared by descriptor alone. */
private fun javaMembers(shape: ClassShape): List<MemberView> =
    shape.methods
        .filter { it.isPublic && !it.isStatic && !it.isSynthetic && it.name != "<init>" && it.name != "<clinit>" }
        .map {
            MemberView(
                jvmName = it.name,
                jvmDescriptor = it.descriptor,
                kotlinName = null,
                isProperty = false,
                isAbstract = it.isAbstract,
                isGeneric = it.signature?.startsWith("<") == true,
            )
        }

internal const val ENUM_NAME_METHOD = "name"

/** Whether a Kotlin value parameter would be rejected as an override's parameter. */
internal fun KmValueParameter.hasDefault(): Boolean = declaresDefaultValue

internal fun KmValueParameter.vararg(): Boolean = varargElementType != null

/** The classifier of a type, as metadata names it (`kotlin/Int`, `com/example/Sized`), or null for type parameters. */
internal fun KmType.classifierName(): String? = (classifier as? KmClassifier.Class)?.name?.replace('.', '$')

internal fun KmType.nullable(): Boolean = isNullable

/** Types built from a type parameter can't be compared without substitution; the plugin treats them as unknown too. */
internal fun KmType.usesTypeParameter(): Boolean =
    classifier is KmClassifier.TypeParameter ||
        arguments.any { it.type?.usesTypeParameter() ?: false }

internal fun KmClass.isGenericHierarchy(): Boolean = typeParameters.isNotEmpty()
