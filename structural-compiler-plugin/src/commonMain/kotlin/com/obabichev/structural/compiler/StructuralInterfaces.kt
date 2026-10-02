@file:OptIn(SymbolInternals::class, DirectDeclarationsAccess::class)

package com.obabichev.structural.compiler

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutor
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutorByMap
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.scopes.processAllFunctions
import org.jetbrains.kotlin.fir.scopes.processAllProperties
import org.jetbrains.kotlin.fir.scopes.unsubstitutedScope
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.contains
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.fir.types.ProjectionKind

internal val STRUCTURAL_ANNOTATION = FqName("com.obabichev.structural.Structural")
internal val STRUCTURAL_PREDICATE = LookupPredicate.create { annotated(STRUCTURAL_ANNOTATION) }

/** Kinds of classes that can gain a @Structural interface as a supertype. */
internal val MATCHABLE_CLASS_KINDS = setOf(ClassKind.CLASS, ClassKind.OBJECT, ClassKind.ENUM_CLASS)

/**
 * A property or function together with the class or interface declaring it, whose file decides how its types resolve.
 *
 * [substitutor] maps the type parameters of a generic interface the member was inherited from to the arguments the
 * @Structural interface gave them: a requirement of `Iterable<String>` is `iterator(): Iterator<String>`, not
 * `Iterator<T>`. It is empty for everything declared without generics, which is every member of a class.
 */
internal class Member(
    val declaration: FirCallableDeclaration,
    val owner: FirRegularClassSymbol,
    val substitutor: ConeSubstitutor = ConeSubstitutor.Empty,
) {
    val name: Name? = when (declaration) {
        is FirProperty -> declaration.name
        is FirNamedFunction -> declaration.name
        else -> null
    }

    /** How the member reads in a message: `width` for a property, `area()` for a function. */
    val label: String get() = if (declaration is FirNamedFunction) "$name()" else name?.asString().orEmpty()
}

/** A @Structural interface that classes can implement by shape, and the abstract members they must provide. */
internal class StructuralInterface(val symbol: FirRegularClassSymbol, val requirements: List<Member>) {
    val classId: ClassId get() = symbol.classId
}

/**
 * The usable @Structural interfaces, remembered across the classes of one compilation.
 *
 * An interface can be undecidable when first asked about: supertypes are resolved file by file, so an interface whose
 * own supertypes this session hasn't reached yet looks like it requires nothing. Caching that answer would refuse it
 * for the rest of the compilation and the classes that match it would silently miss out, so an interface that produced
 * no requirements is asked about again for the next class, while the ones that did are kept.
 */
internal class StructuralInterfaceIndex(
    private val session: FirSession,
    private val types: TypeLookup,
    private val imported: List<ClassId> = emptyList(),
) {
    private val usable = LinkedHashMap<ClassId, StructuralInterface>()

    fun interfaces(): List<StructuralInterface> {
        for (symbol in session.candidateInterfaces(imported)) {
            if (symbol.classId in usable) continue
            session.structuralInterface(symbol, types)?.let { usable[symbol.classId] = it }
        }
        return usable.values.toList()
    }
}

/** Interfaces annotated @Structural in this module, plus the ones dependencies published. */
private fun FirSession.candidateInterfaces(imported: List<ClassId>): List<FirRegularClassSymbol> {
    val declared = predicateBasedProvider.getSymbolsByPredicate(STRUCTURAL_PREDICATE)
        .filterIsInstance<FirRegularClassSymbol>()
    val fromDependencies = imported.mapNotNull { symbolProvider.getClassLikeSymbolByClassId(it) as? FirRegularClassSymbol }
    return (declared + fromDependencies).distinctBy { it.classId }.filter { it.classKind == ClassKind.INTERFACE }
}

/**
 * The usable @Structural interfaces: those declared in the module being compiled, and those [imported] from
 * dependencies that published an index (see [StructuralIndexFile]). A module declaring an interface itself wins over a
 * dependency publishing the same one.
 */
internal fun FirSession.structuralInterfaces(
    types: TypeLookup,
    imported: List<ClassId> = emptyList(),
): List<StructuralInterface> = candidateInterfaces(imported).mapNotNull { structuralInterface(it, types) }

/**
 * Collects the abstract members of [iface] and all its superinterfaces. Returns null when a class can't safely implement
 * the interface by shape: type parameters anywhere in the hierarchy, generic or extension members, or superinterfaces
 * that don't resolve. Adding such an interface could leave a class with members it doesn't implement.
 */
internal fun FirSession.structuralInterface(iface: FirRegularClassSymbol, types: TypeLookup): StructuralInterface? {
    val requirements = mutableListOf<Member>()
    // Members with an implementation in a more derived interface are not required from its superinterfaces.
    val implemented = mutableSetOf<String>()
    val visited = mutableSetOf<ClassId>()

    fun collect(symbol: FirRegularClassSymbol, substitutor: ConeSubstitutor): Boolean {
        if (!visited.add(symbol.classId)) return true
        // The @Structural interface itself may not be generic: its arguments would have to be guessed per class.
        if (symbol.classId == iface.classId && symbol.fir.typeParameters.isNotEmpty()) return false
        for (declaration in declaredMembers(symbol)) {
            val member = Member(declaration, symbol, substitutor)
            val key = member.overrideKey()
            if (!isAbstract(declaration)) {
                implemented += key
                continue
            }
            if (key in implemented) continue
            if (declaration.typeParameters.isNotEmpty() || declaration.receiverParameter != null) return false
            requirements += member
        }
        for (superType in types.superTypes(symbol)) {
            val classId = superType.classId ?: return false
            if (classId == StandardClassIds.Any) continue
            val superSymbol = symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol ?: return false
            if (superSymbol.classKind != ClassKind.INTERFACE) return false
            val inherited = substitutorFor(superSymbol, superType, substitutor) ?: return false
            if (!collect(superSymbol, inherited)) return false
        }
        return true
    }

    if (!collect(iface, ConeSubstitutor.Empty) || requirements.isEmpty()) return null
    return StructuralInterface(iface, requirements)
}

/**
 * The substitution for the members of [superSymbol], reached as [superType] from an interface already substituted by
 * [outer]: each of its type parameters takes the argument written at that position, with the outer substitution applied
 * first so a chain like `A : B<String>`, `B<T> : C<T>` ends up with `C<String>`.
 *
 * Null when the arguments can't be used: a count that doesn't match the parameters, a projection such as `out T`, or an
 * argument still mentioning a type parameter, which a non-generic @Structural interface can't produce.
 */
private fun FirSession.substitutorFor(
    superSymbol: FirRegularClassSymbol,
    superType: ConeKotlinType,
    outer: ConeSubstitutor,
): ConeSubstitutor? {
    val parameters = superSymbol.fir.typeParameters.map { it.symbol }
    if (parameters.isEmpty()) return if (superType.typeArguments.isEmpty()) outer else null
    if (superType.typeArguments.size != parameters.size) return null
    val arguments = superType.typeArguments.map { argument ->
        val type = (argument as? ConeKotlinTypeProjection)?.takeIf { it.kind == ProjectionKind.INVARIANT }?.type
            ?: return null
        outer.substituteOrSelf(type).takeUnless { it.contains { part -> part is ConeTypeParameterType } } ?: return null
    }
    return ConeSubstitutorByMap.create(parameters.zip(arguments).toMap(), this, false)
}

private fun Member.overrideKey(): String = when (val declaration = declaration) {
    is FirNamedFunction -> "fun $name/${declaration.valueParameters.size}"
    else -> "val $name"
}

/** Kotlin source declarations aren't status-resolved yet at the supertype phase; everything else has a modality. */
private fun isAbstract(declaration: FirCallableDeclaration): Boolean {
    if (declaration.origin != FirDeclarationOrigin.Source) return declaration.status.modality == Modality.ABSTRACT
    return when (declaration) {
        is FirProperty -> declaration.getter?.body == null && declaration.initializer == null && declaration.delegate == null
        is FirNamedFunction -> declaration.body == null
        else -> false
    }
}

/**
 * Direct properties and functions of [symbol]. Anything not written in the module being compiled -- a Java class, or an
 * interface published by a dependency -- comes from the class's member scope, which hands out members with their types
 * already known. Their declarations are only filled in on demand, and when IntelliJ analyzes code that demand can't be
 * met while supertypes are being decided, so reading the declarations directly finds no types and nothing matches.
 */
private fun FirSession.declaredMembers(symbol: FirRegularClassSymbol): List<FirCallableDeclaration> {
    if (symbol.fir.origin == FirDeclarationOrigin.Source) {
        return symbol.fir.declarations.filter { it is FirProperty || it is FirNamedFunction }.map { it as FirCallableDeclaration }
    }
    val scope = symbol.unsubstitutedScope(this, ScopeSession(), withForcedTypeCalculator = false, memberRequiredPhase = null)
    val members = mutableListOf<FirCallableDeclaration>()
    scope.processAllFunctions { if (it.callableId.classId == symbol.classId) members += it.fir }
    scope.processAllProperties { if (it is FirPropertySymbol && it.callableId?.classId == symbol.classId) members += it.fir }
    return members
}

/** Properties and functions of [klass] and its superclass chain, nearest first. */
internal fun FirSession.classMembers(klass: FirRegularClassSymbol, supertypes: List<ConeKotlinType>, types: TypeLookup): List<Member> {
    val members = mutableListOf<Member>()
    var current: FirRegularClassSymbol? = klass
    var currentSupertypes = supertypes
    val visited = mutableSetOf<ClassId>()
    while (current != null && visited.add(current.classId)) {
        val owner = current
        declaredMembers(current).mapTo(members) { Member(it, owner) }
        // Members of generic superclasses are collected too; those whose types use type parameters never match.
        val superclass = currentSupertypes
            .mapNotNull { it.classId }
            .mapNotNull { symbolProvider.getClassLikeSymbolByClassId(it) as? FirRegularClassSymbol }
            .firstOrNull { it.classKind == ClassKind.CLASS }
        current = superclass
        currentSupertypes = superclass?.let { types.superTypes(it) }.orEmpty()
    }
    return members
}

/** Whether [members] of a class provide every requirement of [iface]. */
internal fun FirSession.implementsByShape(members: List<Member>, iface: StructuralInterface, types: TypeLookup): Boolean =
    iface.requirements.all { requirement -> members.any { satisfies(it, requirement, types) } }

/**
 * Why [members] of a class don't provide everything [iface] requires, one reason per unmet requirement; empty when the
 * class implements it. Only for diagnostics: [implementsByShape] answers the same question without building messages.
 */
internal fun FirSession.explain(members: List<Member>, iface: StructuralInterface, types: TypeLookup): List<Mismatch> =
    iface.requirements.mapNotNull { requirement ->
        val named = members.filter { it.name != null && it.name == requirement.name }
        if (named.isEmpty()) return@mapNotNull Mismatch.NoSuchMember(requirement.label)
        val reasons = named.map { mismatch(it, requirement, types) }
        // A single matching member is enough; otherwise report the one that came closest.
        if (reasons.any { it == null }) null else reasons.filterNotNull().minByOrNull { it.rank }
    }

/** Whether [member] of a class can implement [requirement]; see [mismatch] for the rules. */
internal fun FirSession.satisfies(member: Member, requirement: Member, types: TypeLookup): Boolean =
    mismatch(member, requirement, types) == null

/**
 * Why [member] of a class can't implement [requirement] under Kotlin override rules, without the class declaring the
 * interface, or null when it can: same name, public, not generic or an extension, and
 * - property: `val` may have a subtype, `var` must be a `var` of exactly the same type with a public setter; not `const`
 * - function: same `suspend`, same parameter names, `vararg` and exact types, no default values (an override can't
 *   declare them), not `inline`, and a return type that is the same or a subtype
 *
 * Nothing is allocated while a member matches, so the supertype phase pays only for failures.
 */
internal fun FirSession.mismatch(member: Member, requirement: Member, types: TypeLookup): Mismatch? {
    val candidate = member.declaration
    val required = requirement.declaration
    val label = requirement.label
    if (member.name == null || member.name != requirement.name) return Mismatch.NoSuchMember(label)
    if (candidate.receiverParameter != null) return Mismatch.Unsupported(label, "is an extension")
    if (candidate.typeParameters.isNotEmpty()) return Mismatch.Unsupported(label, "is generic")
    if (!candidate.status.visibility.isPublicOrDefault()) {
        return Mismatch.NotPublic(label, candidate.status.visibility)
    }

    val expectedReturn = concrete(requirement.substituted(types.returnType(required, requirement.owner)))
        ?: return Mismatch.Unsupported(label, "its type in the interface can't be compared")
    val actualReturn = types.returnType(candidate, member.owner)?.let(::concrete)
        ?: return Mismatch.InferredType(label)

    return when {
        required is FirProperty && candidate is FirProperty -> when {
            candidate.status.isConst -> Mismatch.Unsupported(label, "is const")
            !required.isVar ->
                if (types.isSubtype(actualReturn, expectedReturn)) null
                else Mismatch.PropertyType(label, expectedReturn, actualReturn)
            !candidate.isVar -> Mismatch.NeedsVar(label)
            candidate.setter?.status?.visibility?.isPublicOrDefault() == false -> Mismatch.SetterNotPublic(label)
            !types.isEqual(actualReturn, expectedReturn) -> Mismatch.PropertyType(label, expectedReturn, actualReturn)
            else -> null
        }
        required is FirNamedFunction && candidate is FirNamedFunction ->
            functionMismatch(label, member, requirement, expectedReturn, actualReturn, types)
        required is FirProperty -> Mismatch.Unsupported(label, "is a function, the interface declares a property")
        else -> Mismatch.Unsupported(label, "is a property, the interface declares a function")
    }
}

/** The parameter-by-parameter half of [mismatch], reached once both sides are known to be functions. */
private fun functionMismatch(
    label: String,
    member: Member,
    requirement: Member,
    expectedReturn: ConeKotlinType,
    actualReturn: ConeKotlinType,
    types: TypeLookup,
): Mismatch? {
    val candidate = member.declaration as FirNamedFunction
    val required = requirement.declaration as FirNamedFunction
    if (candidate.status.isSuspend != required.status.isSuspend) {
        return Mismatch.Suspend(label, required.status.isSuspend)
    }
    if (candidate.status.isInline) return Mismatch.Unsupported(label, "is inline")
    if (candidate.valueParameters.size != required.valueParameters.size) {
        return Mismatch.ParameterCount(label, required.valueParameters.size, candidate.valueParameters.size)
    }
    candidate.valueParameters.zip(required.valueParameters).forEachIndexed { at, (actual, expected) ->
        if (actual.name != expected.name) return Mismatch.ParameterName(label, at, expected.name, actual.name)
        if (actual.isVararg != expected.isVararg) return Mismatch.ParameterVararg(label, at, expected.isVararg)
        if (actual.defaultValue != null) return Mismatch.ParameterDefault(label, at)
        val expectedType = concrete(requirement.substituted(types.parameterType(expected, requirement.owner)))
            ?: return Mismatch.Unsupported(label, "a parameter type in the interface can't be compared")
        val actualType = types.parameterType(actual, member.owner)?.let(::concrete)
            ?: return Mismatch.InferredType(label)
        if (!types.isEqual(actualType, expectedType)) {
            return Mismatch.ParameterType(label, at, expectedType, actualType)
        }
    }
    return if (types.isSubtype(actualReturn, expectedReturn)) null
    else Mismatch.ReturnType(label, expectedReturn, actualReturn)
}

/** The type as the @Structural interface sees it, with any inherited type parameters replaced by its arguments. */
private fun Member.substituted(type: ConeKotlinType?): ConeKotlinType? = type?.let(substitutor::substituteOrSelf)

/** Types involving type parameters (of a generic superclass) would need substitution: treat them as unknown. */
private fun concrete(type: ConeKotlinType?): ConeKotlinType? =
    type?.takeUnless { it.contains { part -> part is ConeTypeParameterType } }

/** Before status resolution an unspecified visibility is [Visibilities.Unknown], which means public here. */
private fun Visibility.isPublicOrDefault(): Boolean = this == Visibilities.Public || this == Visibilities.Unknown
