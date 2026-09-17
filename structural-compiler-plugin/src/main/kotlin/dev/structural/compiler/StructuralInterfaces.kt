@file:OptIn(SymbolInternals::class, DirectDeclarationsAccess::class)

package dev.structural.compiler

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
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.scopes.processAllFunctions
import org.jetbrains.kotlin.fir.scopes.processAllProperties
import org.jetbrains.kotlin.fir.scopes.unsubstitutedScope
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.contains
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

internal val STRUCTURAL_ANNOTATION = FqName("dev.structural.Structural")
internal val STRUCTURAL_PREDICATE = LookupPredicate.create { annotated(STRUCTURAL_ANNOTATION) }

/** Kinds of classes that can gain a @Structural interface as a supertype. */
internal val MATCHABLE_CLASS_KINDS = setOf(ClassKind.CLASS, ClassKind.OBJECT, ClassKind.ENUM_CLASS)

/** A property or function together with the class or interface declaring it, whose file decides how its types resolve. */
internal class Member(val declaration: FirCallableDeclaration, val owner: FirRegularClassSymbol) {
    val name: Name? = when (declaration) {
        is FirProperty -> declaration.name
        is FirNamedFunction -> declaration.name
        else -> null
    }
}

/** A @Structural interface that classes can implement by shape, and the abstract members they must provide. */
internal class StructuralInterface(val symbol: FirRegularClassSymbol, val requirements: List<Member>) {
    val classId: ClassId get() = symbol.classId
}

/** The usable @Structural interfaces declared in the module being compiled. */
internal fun FirSession.structuralInterfaces(types: TypeLookup): List<StructuralInterface> =
    predicateBasedProvider.getSymbolsByPredicate(STRUCTURAL_PREDICATE)
        .filterIsInstance<FirRegularClassSymbol>()
        .filter { it.classKind == ClassKind.INTERFACE }
        .mapNotNull { structuralInterface(it, types) }

/**
 * Collects the abstract members of [iface] and all its superinterfaces. Returns null when a class can't safely implement
 * the interface by shape: type parameters anywhere in the hierarchy, generic or extension members, or superinterfaces
 * that don't resolve. Adding such an interface could leave a class with members it doesn't implement.
 */
private fun FirSession.structuralInterface(iface: FirRegularClassSymbol, types: TypeLookup): StructuralInterface? {
    val requirements = mutableListOf<Member>()
    // Members with an implementation in a more derived interface are not required from its superinterfaces.
    val implemented = mutableSetOf<String>()
    val visited = mutableSetOf<ClassId>()

    fun collect(symbol: FirRegularClassSymbol): Boolean {
        if (!visited.add(symbol.classId)) return true
        if (symbol.fir.typeParameters.isNotEmpty()) return false
        for (declaration in declaredMembers(symbol)) {
            val member = Member(declaration, symbol)
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
            if (superSymbol.classKind != ClassKind.INTERFACE || !collect(superSymbol)) return false
        }
        return true
    }

    if (!collect(iface) || requirements.isEmpty()) return null
    return StructuralInterface(iface, requirements)
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
 * Direct properties and functions of [symbol]. Members of Java classes come from the class's member scope, which has
 * their types converted to Kotlin; the Java declarations themselves are only converted lazily.
 */
private fun FirSession.declaredMembers(symbol: FirRegularClassSymbol): List<FirCallableDeclaration> {
    if (symbol.fir.origin !is FirDeclarationOrigin.Java) {
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
 * Whether [member] of a class can implement [requirement] under Kotlin override rules, without the class declaring the
 * interface: same name, public, not generic or an extension, and
 * - property: `val` may have a subtype, `var` must be a `var` of exactly the same type with a public setter; not `const`
 * - function: same `suspend`, same parameter names, `vararg` and exact types, no default values (an override can't
 *   declare them), not `inline`, and a return type that is the same or a subtype
 */
internal fun FirSession.satisfies(member: Member, requirement: Member, types: TypeLookup): Boolean {
    val candidate = member.declaration
    val required = requirement.declaration
    if (member.name == null || member.name != requirement.name) return false
    if (candidate.receiverParameter != null || candidate.typeParameters.isNotEmpty()) return false
    if (!candidate.status.visibility.isPublicOrDefault()) return false

    // Types involving type parameters (of a generic superclass) would need substitution: treat them as unknown.
    fun concrete(type: ConeKotlinType?) = type?.takeUnless { it.contains { part -> part is ConeTypeParameterType } }
    val expectedReturn = concrete(types.returnType(required, requirement.owner)) ?: return false
    val actualReturn = concrete(types.returnType(candidate, member.owner)) ?: return false

    return when {
        required is FirProperty && candidate is FirProperty -> {
            if (candidate.status.isConst) return false
            if (!required.isVar) return types.isSubtype(actualReturn, expectedReturn)
            candidate.isVar &&
                candidate.setter?.status?.visibility?.isPublicOrDefault() != false &&
                types.isEqual(actualReturn, expectedReturn)
        }
        required is FirNamedFunction && candidate is FirNamedFunction -> {
            if (candidate.status.isSuspend != required.status.isSuspend || candidate.status.isInline) return false
            if (candidate.valueParameters.size != required.valueParameters.size) return false
            val parametersMatch = candidate.valueParameters.zip(required.valueParameters).all { (actual, expected) ->
                val actualType = concrete(types.parameterType(actual, member.owner))
                val expectedType = concrete(types.parameterType(expected, requirement.owner))
                actual.name == expected.name &&
                    actual.isVararg == expected.isVararg &&
                    actual.defaultValue == null &&
                    actualType != null && expectedType != null &&
                    types.isEqual(actualType, expectedType)
            }
            parametersMatch && types.isSubtype(actualReturn, expectedReturn)
        }
        else -> false
    }
}

/** Before status resolution an unspecified visibility is [Visibilities.Unknown], which means public here. */
private fun Visibility.isPublicOrDefault(): Boolean = this == Visibilities.Public || this == Visibilities.Unknown
