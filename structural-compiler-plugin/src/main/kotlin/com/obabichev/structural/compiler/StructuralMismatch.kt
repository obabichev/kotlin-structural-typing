package com.obabichev.structural.compiler

import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.renderReadable
import org.jetbrains.kotlin.name.Name

/**
 * Why a class doesn't provide what a @Structural interface requires. Every case is one reason [mismatch] returns instead
 * of matching a member, so a diagnostic can say what to change rather than only that the class didn't match.
 *
 * [member] is the requirement as it reads in a message: `width` for a property, `area()` for a function.
 */
internal sealed interface Mismatch {
    val member: String

    /** No member of that name at all. */
    data class NoSuchMember(override val member: String) : Mismatch

    data class NotPublic(override val member: String, val visibility: Visibility) : Mismatch

    /** The class member's type is inferred; supertypes are decided before inferred types are known. */
    data class InferredType(override val member: String) : Mismatch

    data class PropertyType(
        override val member: String,
        val expected: ConeKotlinType,
        val actual: ConeKotlinType,
    ) : Mismatch

    data class ReturnType(
        override val member: String,
        val expected: ConeKotlinType,
        val actual: ConeKotlinType,
    ) : Mismatch

    /** The interface requires a `var`, so an override can't narrow it to a `val`. */
    data class NeedsVar(override val member: String) : Mismatch

    data class SetterNotPublic(override val member: String) : Mismatch

    data class ParameterCount(override val member: String, val expected: Int, val actual: Int) : Mismatch

    data class ParameterName(override val member: String, val at: Int, val expected: Name, val actual: Name) : Mismatch

    data class ParameterType(
        override val member: String,
        val at: Int,
        val expected: ConeKotlinType,
        val actual: ConeKotlinType,
    ) : Mismatch

    /** An override can't declare default values. */
    data class ParameterDefault(override val member: String, val at: Int) : Mismatch

    data class ParameterVararg(override val member: String, val at: Int, val expectedVararg: Boolean) : Mismatch

    data class Suspend(override val member: String, val expectedSuspend: Boolean) : Mismatch

    /** The member can't take part in matching at all: generic, an extension, `const`, `inline`, the wrong kind. */
    data class Unsupported(override val member: String, val reason: String) : Mismatch
}

/** One line of a diagnostic, naming the member and what to change. */
internal fun Mismatch.render(): String = when (this) {
    is Mismatch.NoSuchMember -> "$member: missing"
    is Mismatch.NotPublic -> "$member: is ${visibility.name}, must be public"
    is Mismatch.InferredType -> "$member: has an inferred type, declare it explicitly"
    is Mismatch.PropertyType -> "$member: is '${actual.renderReadable()}', expected '${expected.renderReadable()}'"
    is Mismatch.ReturnType -> "$member: returns '${actual.renderReadable()}', expected '${expected.renderReadable()}'"
    is Mismatch.NeedsVar -> "$member: is a val, the interface declares a var"
    is Mismatch.SetterNotPublic -> "$member: its setter is not public"
    is Mismatch.ParameterCount -> "$member: takes $actual parameters, expected $expected"
    is Mismatch.ParameterName -> "$member: parameter ${at + 1} is named '$actual', expected '$expected'"
    is Mismatch.ParameterType ->
        "$member: parameter ${at + 1} is '${actual.renderReadable()}', expected '${expected.renderReadable()}'"
    is Mismatch.ParameterDefault -> "$member: parameter ${at + 1} has a default value, which an override can't declare"
    is Mismatch.ParameterVararg ->
        if (expectedVararg) "$member: parameter ${at + 1} is not vararg, the interface declares vararg"
        else "$member: parameter ${at + 1} is vararg, the interface doesn't declare it"
    is Mismatch.Suspend ->
        if (expectedSuspend) "$member: is not suspend, the interface declares it suspend"
        else "$member: is suspend, the interface doesn't declare it"
    is Mismatch.Unsupported -> "$member: $reason"
}

/**
 * How close the member came, lowest first. When several members share a name, the closest one is reported: a wrong type
 * is a smaller fix than a wrong parameter list, and both are more useful than "missing".
 */
internal val Mismatch.rank: Int
    get() = when (this) {
        is Mismatch.PropertyType, is Mismatch.ReturnType, is Mismatch.NeedsVar, is Mismatch.SetterNotPublic,
        is Mismatch.NotPublic, is Mismatch.InferredType,
        -> 0
        is Mismatch.ParameterName, is Mismatch.ParameterType, is Mismatch.ParameterDefault,
        is Mismatch.ParameterVararg, is Mismatch.Suspend,
        -> 1
        is Mismatch.ParameterCount -> 2
        is Mismatch.Unsupported -> 3
        is Mismatch.NoSuchMember -> 4
    }
