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
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutor
import org.jetbrains.kotlin.fir.resolve.substitution.ChainedSubstitutor
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutorByMap
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.scopes.processAllFunctions
import org.jetbrains.kotlin.fir.scopes.processAllProperties
import org.jetbrains.kotlin.fir.scopes.unsubstitutedScope
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.fir.types.toLookupTag
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

/**
 * A @Structural interface that classes can implement by shape, and the abstract members they must provide.
 *
 * [typeParameters] is empty for an ordinary interface. A generic one is matched per class: its arguments are solved
 * from the members of the class in front of it, so `IntBox(val value: Int)` implements `Box<Int>`.
 */
internal class StructuralInterface(
    val symbol: FirRegularClassSymbol,
    val requirements: List<Member>,
    val typeParameters: List<FirTypeParameterSymbol> = emptyList(),
) {
    val classId: ClassId get() = symbol.classId
    val isGeneric: Boolean get() = typeParameters.isNotEmpty()

    /** The same interface with [arguments] put in place of its parameters, so its requirements are concrete types. */
    fun substituted(session: FirSession, arguments: List<ConeKotlinType>): StructuralInterface {
        if (arguments.isEmpty()) return this
        val substitutor = session.substitutorFor(this, arguments)
        return StructuralInterface(
            symbol,
            requirements.map { Member(it.declaration, it.owner, ChainedSubstitutor(it.substitutor, substitutor)) },
        )
    }
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
            session.structuralInterfaceOrNull(symbol, types)?.let { usable[symbol.classId] = it }
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
): List<StructuralInterface> = candidateInterfaces(imported).mapNotNull { structuralInterfaceOrNull(it, types) }

/**
 * Collects the abstract members of [iface] and all its superinterfaces. Returns null when a class can't safely implement
 * the interface by shape: type parameters anywhere in the hierarchy, generic or extension members, or superinterfaces
 * that don't resolve. Adding such an interface could leave a class with members it doesn't implement.
 */
/**
 * Why an interface can't be used, when it can't. [Unusable] is final -- the interface can never be added to any class,
 * and the user is told so -- while [Undecided] only means this session hasn't resolved enough yet and the answer should
 * be asked for again.
 */
internal sealed interface InterfaceResult {
    data class Usable(val iface: StructuralInterface) : InterfaceResult
    data class Unusable(val reason: String) : InterfaceResult
    object Undecided : InterfaceResult
}

/** [structuralInterface] without the reason, for callers that only need the usable ones. */
internal fun FirSession.structuralInterfaceOrNull(iface: FirRegularClassSymbol, types: TypeLookup): StructuralInterface? =
    (structuralInterface(iface, types) as? InterfaceResult.Usable)?.iface

internal fun FirSession.structuralInterface(iface: FirRegularClassSymbol, types: TypeLookup): InterfaceResult {
    val requirements = mutableListOf<Member>()
    // Members with an implementation in a more derived interface are not required from its superinterfaces.
    val implemented = mutableSetOf<String>()
    val visited = mutableSetOf<ClassId>()

    fun collect(symbol: FirRegularClassSymbol, substitutor: ConeSubstitutor): InterfaceResult? {
        if (!visited.add(symbol.classId)) return null
        for (declaration in declaredMembers(symbol)) {
            val member = Member(declaration, symbol, substitutor)
            val key = member.overrideKey()
            if (!isAbstract(declaration)) {
                implemented += key
                continue
            }
            if (key in implemented) continue
            if (declaration.receiverParameter != null) {
                return InterfaceResult.Unusable("'${member.label}' is an extension, which a class can't implement")
            }
            requirements += member
        }
        for (superType in types.superTypes(symbol)) {
            val classId = superType.classId ?: return InterfaceResult.Undecided
            if (classId == StandardClassIds.Any) continue
            val superSymbol = symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
                ?: return InterfaceResult.Undecided
            if (superSymbol.classKind != ClassKind.INTERFACE) {
                return InterfaceResult.Unusable("'${classId.asFqNameString()}' is a supertype that is not an interface")
            }
            val inherited = substitutorFor(superSymbol, superType, substitutor)
                ?: return InterfaceResult.Unusable(
                    "the arguments it gives '${classId.asFqNameString()}' can't be used: a projection such as " +
                        "`out T`, or a type parameter of its own",
                )
            collect(superSymbol, inherited)?.let { return it }
        }
        return null
    }

    collect(iface, ConeSubstitutor.Empty)?.let { return it }
    // An interface that requires nothing may simply not be resolved yet, so this is not reported to the user.
    if (requirements.isEmpty()) return InterfaceResult.Undecided

    val parameters = iface.fir.typeParameters.map { it.symbol }
    val unreachable = parameters.filterNot { parameter ->
        requirements.any { requirement -> requirement.mentions(parameter, types) }
    }
    if (unreachable.isNotEmpty()) {
        return InterfaceResult.Unusable(
            "no member mentions ${unreachable.joinToString { "'${it.name}'" }}, so a class can't say what it is",
        )
    }
    return InterfaceResult.Usable(StructuralInterface(iface, requirements, parameters))
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
    if (!candidate.status.visibility.isPublicOrDefault()) {
        return Mismatch.NotPublic(label, candidate.status.visibility)
    }
    // A generic requirement is matched by a generic member of the same shape, its parameters read as the class's own.
    val renaming = when (val lined = typeParameterRenaming(member, requirement, label, types)) {
        is Renaming.Of -> lined.substitutor
        is Renaming.No -> return lined.mismatch
    }

    val expectedReturn = comparable(requirement.substituted(types.returnType(required, requirement.owner)), renaming)
        ?: return Mismatch.Unsupported(label, "its type in the interface can't be compared")
    val actualReturn = comparable(types.returnType(candidate, member.owner), ConeSubstitutor.Empty, renaming)
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
            functionMismatch(label, member, requirement, expectedReturn, actualReturn, types, renaming)
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
    renaming: ConeSubstitutor,
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
        val expectedType = comparable(requirement.substituted(types.parameterOf(expected, requirement)), renaming)
            ?: return Mismatch.Unsupported(label, "a parameter type in the interface can't be compared")
        val actualType = comparable(types.parameterOf(actual, member), ConeSubstitutor.Empty, renaming)
            ?: return Mismatch.InferredType(label)
        if (!types.isEqual(actualType, expectedType)) {
            return Mismatch.ParameterType(label, at, expectedType, actualType)
        }
    }
    return if (types.isSubtype(actualReturn, expectedReturn)) null
    else Mismatch.ReturnType(label, expectedReturn, actualReturn)
}

/** Whether [parameter] appears in this requirement's own types, which is what lets a class decide what it is. */
private fun Member.mentions(parameter: FirTypeParameterSymbol, types: TypeLookup): Boolean {
    val own = listOfNotNull(types.returnType(declaration, owner)) +
        (declaration as? FirNamedFunction)?.valueParameters?.mapNotNull { types.parameterType(it, owner) }.orEmpty()
    return own.any { type -> type.contains { it is ConeTypeParameterType && it.lookupTag.symbol == parameter } }
}

/** The type as the @Structural interface sees it, with any inherited type parameters replaced by its arguments. */
internal fun Member.substituted(type: ConeKotlinType?): ConeKotlinType? = type?.let(substitutor::substituteOrSelf)

/** How a generic requirement's type parameters line up with the candidate's, or why they don't. */
private sealed interface Renaming {
    data class Of(val substitutor: ConeSubstitutor) : Renaming
    data class No(val mismatch: Mismatch) : Renaming
}

/**
 * A requirement such as `fun <T> map(value: T): T` is matched by a member declaring as many type parameters, with the
 * same bounds, and the rest of the signature equal once the requirement's parameters are read as the candidate's --
 * which is what Kotlin asks of a hand-written override, where only the names may differ.
 */
private fun FirSession.typeParameterRenaming(
    member: Member,
    requirement: Member,
    label: String,
    types: TypeLookup,
): Renaming {
    val wanted = requirement.declaration.typeParameters
    val given = member.declaration.typeParameters
    if (wanted.isEmpty() && given.isEmpty()) return Renaming.Of(ConeSubstitutor.Empty)
    if (wanted.isEmpty()) return Renaming.No(Mismatch.Unsupported(label, "is generic, the interface's member is not"))
    if (given.isEmpty()) return Renaming.No(Mismatch.Unsupported(label, "is not generic, the interface's member is"))
    if (wanted.size != given.size) {
        return Renaming.No(
            Mismatch.Unsupported(label, "declares ${given.size} type parameters, the interface's member ${wanted.size}"),
        )
    }
    val substitutor = ConeSubstitutorByMap.create(
        wanted.indices.associate { at ->
            wanted[at].symbol to ConeTypeParameterTypeImpl(given[at].symbol.toLookupTag(), isMarkedNullable = false)
        },
        this,
        false,
    )
    for (at in wanted.indices) {
        val wantedBounds = wanted[at].symbol.fir.bounds.mapNotNull { types.type(it, requirement.owner) }
        val givenBounds = given[at].symbol.fir.bounds.mapNotNull { types.type(it, member.owner) }
        val sameBounds = wantedBounds.size == givenBounds.size &&
            wantedBounds.zip(givenBounds).all { (want, give) ->
                types.isEqual(give, substitutor.substituteOrSelf(want))
            }
        if (!sameBounds) {
            return Renaming.No(
                Mismatch.Unsupported(label, "its type parameter ${at + 1} has different bounds from the interface's"),
            )
        }
    }
    return Renaming.Of(substitutor)
}

/**
 * A type ready to compare: the requirement's own parameters replaced by [substitutor], and parameters left over only
 * tolerated when the member is itself generic, where they are the class's and stand for themselves. Everywhere else a
 * leftover parameter means a member of a generic superclass, whose type this plugin can't decide.
 */
private fun comparable(
    type: ConeKotlinType?,
    substitutor: ConeSubstitutor,
    renaming: ConeSubstitutor = substitutor,
): ConeKotlinType? {
    val substituted = type?.let(substitutor::substituteOrSelf) ?: return null
    return if (renaming != ConeSubstitutor.Empty) substituted else concrete(substituted)
}

/** A value parameter's type, with the type parameters of the member declaring it in scope. */
private fun TypeLookup.parameterOf(parameter: FirValueParameter, member: Member): ConeKotlinType? =
    if (this is SupertypePhaseTypes) parameterType(parameter, member.owner, member.declaration)
    else parameterType(parameter, member.owner)

/** Types involving type parameters (of a generic superclass) would need substitution: treat them as unknown. */
private fun concrete(type: ConeKotlinType?): ConeKotlinType? =
    type?.takeUnless { it.contains { part -> part is ConeTypeParameterType } }

/** Before status resolution an unspecified visibility is [Visibilities.Unknown], which means public here. */
private fun Visibility.isPublicOrDefault(): Boolean = this == Visibilities.Public || this == Visibilities.Unknown
