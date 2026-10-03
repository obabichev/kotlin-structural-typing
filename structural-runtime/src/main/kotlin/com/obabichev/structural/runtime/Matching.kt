package com.obabichev.structural.runtime

import kotlin.metadata.KmType

/**
 * A `@Structural` interface that classes can implement by shape, and the members they must provide. Mirrors
 * `StructuralInterface` in the compiler plugin; the requirements are collected by the same rules so that a class the
 * plugin would match in source is the class this matches in bytecode.
 */
internal class StructuralInterfaceShape(val internalName: String, val requirements: List<MemberView>)

/**
 * Decides which `@Structural` interfaces a class implements by shape, reading everything from class file bytes.
 *
 * The rules mirror `satisfies()` in the compiler plugin: same name, public, not generic or an extension, and
 * - property: `val` may have a subtype, `var` must be a `var` of exactly the same type with a public setter; not `const`
 * - function: same `suspend`, same parameter names, `vararg` and exact types, no default values, not `inline`, and a
 *   return type that is the same or a subtype
 *
 * It cannot be identical to the plugin's relation and does not pretend to be: the plugin decides during supertype
 * resolution, where types resolve through file-level imports and members of generic superclasses are skipped. This one
 * reads erased, fully resolved bytecode, so it is generally the more permissive of the two. See docs/runtime.md.
 */
internal class StructuralMatcher(private val shapes: ClassShapes) {

    /**
     * The requirements of [internalName], or null when a class can't safely implement it by shape: type parameters
     * anywhere in the hierarchy, generic or extension members, a superinterface that doesn't resolve, no requirements
     * at all, or a sealed interface, which rejects implementors it doesn't permit with IncompatibleClassChangeError.
     */
    fun structuralInterface(internalName: String): StructuralInterfaceShape? {
        val requirements = mutableListOf<MemberView>()
        val implemented = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun collect(name: String): Boolean {
            if (!visited.add(name)) return true
            val shape = shapes.of(name) ?: return false
            if (!shape.isInterface || shape.sealed || shape.isGeneric) return false
            if (shape.kotlin?.isGenericHierarchy() == true) return false
            for (member in declaredMembers(shape)) {
                val key = member.overrideKey()
                if (!member.isAbstract) {
                    implemented += key
                    continue
                }
                if (key in implemented) continue
                if (member.isGeneric || member.hasReceiver) return false
                requirements += member
            }
            return shape.interfaces.all { collect(it) }
        }

        if (!collect(internalName) || requirements.isEmpty()) return null
        return StructuralInterfaceShape(internalName, requirements)
    }

    /** Properties and functions of [internalName] and its superclass chain, nearest first. */
    fun classMembers(internalName: String): List<MemberView> =
        shapes.superclassChain(internalName).flatMap { declaredMembers(it) }

    fun implementsByShape(members: List<MemberView>, iface: StructuralInterfaceShape): Boolean =
        iface.requirements.all { requirement -> members.any { satisfies(it, requirement) } }

    /** The class member that implements [requirement], if any. The transformer needs it to emit the bridge. */
    fun implementationOf(members: List<MemberView>, requirement: MemberView): MemberView? =
        members.firstOrNull { satisfies(it, requirement) }

    fun satisfies(candidate: MemberView, requirement: MemberView): Boolean {
        if (!namesMatch(candidate, requirement)) return false
        if (candidate.hasReceiver || candidate.isGeneric || !candidate.isPublic || candidate.isAbstract) return false
        if (candidate.isProperty != requirement.isProperty && requirement.isProperty) return false

        return if (requirement.isProperty) {
            if (candidate.isConst || !candidate.isProperty) return false
            if (!requirement.isVar) {
                isSubtype(candidate.returnType, candidate.returnDescriptor, requirement.returnType, requirement.returnDescriptor)
            } else {
                candidate.isVar && candidate.setterPublic && candidate.setterName != null &&
                    isEqual(candidate.returnType, candidate.returnDescriptor, requirement.returnType, requirement.returnDescriptor)
            }
        } else {
            if (candidate.isProperty || candidate.isInline) return false
            if (candidate.isSuspend != requirement.isSuspend) return false
            parametersMatch(candidate, requirement) &&
                isSubtype(candidate.returnType, candidate.returnDescriptor, requirement.returnType, requirement.returnDescriptor)
        }
    }

    /**
     * Kotlin members are matched by their Kotlin name, the way the plugin does — the JVM name can differ from it
     * through `@JvmName`, the `isReady` accessor rule, or an enum's built-in `name`. Java members have only a JVM name.
     */
    private fun namesMatch(candidate: MemberView, requirement: MemberView): Boolean =
        if (candidate.kotlinName != null && requirement.kotlinName != null) {
            candidate.kotlinName == requirement.kotlinName
        } else {
            candidate.jvmName == requirement.jvmName
        }

    private fun parametersMatch(candidate: MemberView, requirement: MemberView): Boolean {
        val actual = candidate.parameters
        val expected = requirement.parameters
        if (actual == null || expected == null) {
            // At least one side is Java: only the erased signature is available.
            return candidate.parameterDescriptor == requirement.parameterDescriptor
        }
        if (actual.size != expected.size) return false
        if (candidate.parameterDescriptor != requirement.parameterDescriptor) return false
        return actual.zip(expected).all { (a, e) ->
            a.name == e.name && a.vararg() == e.vararg() && !a.hasDefault() &&
                isEqual(a.type, null, e.type, null)
        }
    }

    /**
     * Type equality. Metadata is used when both sides have it, because `String` and `String?` share a descriptor and
     * `List<String>` and `List<CharSequence>` share an erasure. Otherwise the erased descriptors decide, which is the
     * same concession the plugin makes for Java platform types.
     */
    private fun isEqual(actual: KmType?, actualDescriptor: String?, expected: KmType?, expectedDescriptor: String?): Boolean {
        if (actual != null && expected != null) {
            if (actual.usesTypeParameter() || expected.usesTypeParameter()) return false
            if (actual.classifierName() != expected.classifierName()) return false
            if (actual.nullable() != expected.nullable()) return false
            if (actual.arguments.size != expected.arguments.size) return false
            return actual.arguments.zip(expected.arguments).all { (a, e) ->
                val at = a.type
                val et = e.type
                at != null && et != null && a.variance == e.variance && isEqual(at, null, et, null)
            }
        }
        return actualDescriptor != null && actualDescriptor == expectedDescriptor
    }

    private fun isSubtype(actual: KmType?, actualDescriptor: String, expected: KmType?, expectedDescriptor: String): Boolean {
        if (actual?.usesTypeParameter() == true || expected?.usesTypeParameter() == true) return false
        if (isEqual(actual, actualDescriptor, expected, expectedDescriptor)) return true
        // A nullable requirement accepts a non-null value; the other direction would drop the null.
        if (actual != null && expected != null && expected.nullable() && !actual.nullable() &&
            actual.classifierName() == expected.classifierName()
        ) {
            return true
        }
        val actualClass = erasedInternalName(actualDescriptor) ?: return false
        val expectedClass = erasedInternalName(expectedDescriptor) ?: return false
        return shapes.inheritsFrom(actualClass, expectedClass)
    }
}

/** Members are the same override slot when their name and arity agree, as in the plugin's `overrideKey`. */
internal fun MemberView.overrideKey(): String =
    if (isProperty) "val $kotlinName" else "fun $kotlinName/${parameters?.size ?: parameterDescriptor.length}"

/**
 * The class a descriptor stands for, with primitives boxed so that `Int` can be compared with `Number`. Arrays and
 * `void` have no useful supertype to walk and never take part in a subtype check here.
 */
internal fun erasedInternalName(descriptor: String): String? = when (descriptor) {
    "I" -> "java/lang/Integer"
    "J" -> "java/lang/Long"
    "S" -> "java/lang/Short"
    "B" -> "java/lang/Byte"
    "C" -> "java/lang/Character"
    "Z" -> "java/lang/Boolean"
    "F" -> "java/lang/Float"
    "D" -> "java/lang/Double"
    "V" -> null
    else -> if (descriptor.startsWith("L") && descriptor.endsWith(";")) descriptor.substring(1, descriptor.length - 1) else null
}
