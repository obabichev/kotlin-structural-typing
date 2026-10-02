@file:OptIn(SymbolInternals::class)

package com.obabichev.structural.compiler

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutor
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutorByMap
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible

/**
 * What a class says the type parameters of a generic @Structural interface are.
 *
 * `IntBox(val value: Int)` in front of `interface Box<T> { val value: T }` says `T` is `Int`, so it implements
 * `Box<Int>`. The argument is read off the class rather than chosen: the most specific answer is the only one that is
 * derivable, where `Box<Number>` or `Box<out Number>` would be guesses.
 *
 * A parameter every member agrees on is solved; one the members disagree about, or that the class doesn't pin down, or
 * that breaks the parameter's own bounds, leaves the interface off the class entirely. Guessing in those cases would
 * give a class an interface its author never meant it to have.
 */
internal fun FirSession.solveArguments(
    members: List<Member>,
    iface: StructuralInterface,
    types: TypeLookup,
): List<ConeKotlinType>? {
    if (!iface.isGeneric) return emptyList()
    val solved = mutableMapOf<FirTypeParameterSymbol, ConeKotlinType>()

    for (requirement in iface.requirements) {
        // Only a member of the same name can say anything about the parameters; the rest is ordinary matching.
        val candidates = members.filter { it.name != null && it.name == requirement.name }
        if (candidates.isEmpty()) return null
        val candidate = candidates.firstOrNull { pins(it, requirement, types, solved) } ?: return null
        if (!pins(candidate, requirement, types, solved)) return null
    }

    val arguments = iface.typeParameters.map { solved[it] ?: return null }
    if (!withinBounds(iface, arguments, types)) return null
    return arguments
}

/** Reads what [candidate] says about the parameters still open in [solved]; false when it contradicts them. */
private fun FirSession.pins(
    candidate: Member,
    requirement: Member,
    types: TypeLookup,
    solved: MutableMap<FirTypeParameterSymbol, ConeKotlinType>,
): Boolean {
    val found = mutableMapOf<FirTypeParameterSymbol, ConeKotlinType>()

    val required = requirement.substituted(types.returnType(requirement.declaration, requirement.owner)) ?: return false
    val actual = types.returnType(candidate.declaration, candidate.owner) ?: return false
    if (!unify(required, actual, found, types)) return false

    val requiredFunction = requirement.declaration as? FirNamedFunction
    val actualFunction = candidate.declaration as? FirNamedFunction
    if (requiredFunction != null && actualFunction != null) {
        if (requiredFunction.valueParameters.size != actualFunction.valueParameters.size) return false
        for ((expected, provided) in requiredFunction.valueParameters.zip(actualFunction.valueParameters)) {
            val expectedType = requirement.substituted(types.parameterType(expected, requirement.owner)) ?: return false
            val providedType = types.parameterType(provided, candidate.owner) ?: return false
            if (!unify(expectedType, providedType, found, types)) return false
        }
    }

    for ((parameter, type) in found) {
        val existing = solved[parameter]
        if (existing != null && !types.isEqual(existing, type)) return false
    }
    solved += found
    return true
}

/**
 * Matches [required], which may mention type parameters, against the [actual] type a class offers, recording what each
 * parameter must be. Types with arguments are walked in step; anything else has to be equal already.
 */
private fun unify(
    required: ConeKotlinType,
    actual: ConeKotlinType,
    found: MutableMap<FirTypeParameterSymbol, ConeKotlinType>,
    types: TypeLookup,
): Boolean {
    if (required is ConeTypeParameterType) {
        val parameter = required.lookupTag.symbol
        val previous = found[parameter]
        if (previous != null) return types.isEqual(previous, actual)
        found[parameter] = actual
        return true
    }
    val expected = required.lowerBoundIfFlexible() as? ConeClassLikeType ?: return false
    val provided = actual.lowerBoundIfFlexible() as? ConeClassLikeType ?: return false
    if (expected.classId != provided.classId) return false
    if (expected.isMarkedNullable != provided.isMarkedNullable) return false
    if (expected.typeArguments.size != provided.typeArguments.size) return false
    return expected.typeArguments.zip(provided.typeArguments).all { (expectedArgument, providedArgument) ->
        val expectedType = (expectedArgument as? ConeKotlinTypeProjection)?.type ?: return@all false
        val providedType = (providedArgument as? ConeKotlinTypeProjection)?.type ?: return@all false
        expectedArgument.kind == providedArgument.kind && unify(expectedType, providedType, found, types)
    }
}

/** The arguments a class gives have to satisfy the bounds the interface declared, as they would if written by hand. */
private fun FirSession.withinBounds(
    iface: StructuralInterface,
    arguments: List<ConeKotlinType>,
    types: TypeLookup,
): Boolean {
    val substitutor = substitutorFor(iface, arguments)
    return iface.typeParameters.zip(arguments).all { (parameter, argument) ->
        // The bounds are read the way every other type here is: asking the symbol would resolve them too early.
        parameter.fir.bounds.all { bound ->
            val expected = types.type(bound, iface.symbol)?.let(substitutor::substituteOrSelf) ?: return@all true
            expected.isAnyOrNullableAny() || types.isSubtype(argument, expected)
        }
    }
}

/** Maps the interface's parameters to the arguments a class gives them. */
internal fun FirSession.substitutorFor(iface: StructuralInterface, arguments: List<ConeKotlinType>): ConeSubstitutor =
    if (arguments.isEmpty()) {
        ConeSubstitutor.Empty
    } else {
        ConeSubstitutorByMap.create(iface.typeParameters.zip(arguments).toMap(), this, false)
    }

private fun ConeKotlinType.isAnyOrNullableAny(): Boolean =
    (lowerBoundIfFlexible() as? ConeClassLikeType)?.classId?.asString() == "kotlin/Any"
