@file:OptIn(SymbolInternals::class, DirectDeclarationsAccess::class)

package dev.structural.compiler

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.types.AbstractTypeChecker

internal val STRUCTURAL_ANNOTATION = FqName("dev.structural.Structural")
internal val STRUCTURAL_PREDICATE = LookupPredicate.create { annotated(STRUCTURAL_ANNOTATION) }

/**
 * Kinds of classes that can gain a @Structural interface as a supertype. Enum classes are excluded: the compiler ignores
 * supertypes a plugin adds to them.
 */
internal val MATCHABLE_CLASS_KINDS = setOf(ClassKind.CLASS, ClassKind.OBJECT)

/**
 * @Structural interfaces declared in the module being compiled that a class can implement by having matching properties:
 * interfaces without type parameters, abstract functions or superinterfaces. Adding any other interface could leave the
 * class with unimplemented members, which would break its compilation.
 */
internal fun FirSession.structuralInterfaces(): List<FirRegularClassSymbol> =
    predicateBasedProvider.getSymbolsByPredicate(STRUCTURAL_PREDICATE)
        .filterIsInstance<FirRegularClassSymbol>()
        .filter { iface ->
            iface.classKind == ClassKind.INTERFACE &&
                iface.fir.typeParameters.isEmpty() &&
                iface.fir.declarations.none { it is FirNamedFunction && it.body == null } &&
                iface.fir.superTypeRefs.all { (it as? FirResolvedTypeRef)?.coneType?.classId == StandardClassIds.Any }
        }

/** Abstract properties declared directly in [iface]; properties with a default getter are not required. */
internal fun requiredProperties(iface: FirRegularClassSymbol): List<FirProperty> =
    iface.fir.declarations.filterIsInstance<FirProperty>().filter { it.getter?.body == null }

/** How to get a property's type. During supertype resolution only explicitly declared types are available. */
internal fun interface PropertyTypes {
    fun of(property: FirProperty): ConeKotlinType?
}

/** Properties of the superclass chain (not interfaces), farthest first so that nearer declarations win. */
internal fun FirSession.superclassProperties(supertypeRefs: List<FirTypeRef>): List<FirProperty> {
    val result = mutableListOf<FirProperty>()
    var current = superclassOf(supertypeRefs)
    while (current != null) {
        result.addAll(0, current.fir.declarations.filterIsInstance<FirProperty>())
        current = superclassOf(current.fir.superTypeRefs)
    }
    return result
}

private fun FirSession.superclassOf(supertypeRefs: List<FirTypeRef>): FirRegularClassSymbol? =
    supertypeRefs
        .mapNotNull { (it as? FirResolvedTypeRef)?.coneType?.classId }
        .mapNotNull { symbolProvider.getClassLikeSymbolByClassId(it) as? FirRegularClassSymbol }
        .firstOrNull { it.classKind == ClassKind.CLASS && it.fir.typeParameters.isEmpty() }

/**
 * Whether a class with [ownProperties] and [inheritedProperties] can implement [iface] under Kotlin override rules:
 * every required property exists and is public, a `val` may have a subtype, and a `var` must be a `var` of exactly
 * the same type with a public setter.
 */
internal fun FirSession.matches(
    ownProperties: List<FirProperty>,
    inheritedProperties: List<FirProperty>,
    iface: FirRegularClassSymbol,
    types: PropertyTypes,
): Boolean {
    val required = requiredProperties(iface)
    if (required.isEmpty()) return false
    val properties = (inheritedProperties + ownProperties).associateBy { it.name }
    return required.all { requirement ->
        val property = properties[requirement.name] ?: return false
        if (!property.status.visibility.isPublicOrDefault()) return false
        if (requirement.isVar && (!property.isVar || property.setter?.status?.visibility?.isPublicOrDefault() == false)) {
            return false
        }
        val expected = types.of(requirement) ?: return false
        val actual = types.of(property) ?: return false
        if (requirement.isVar) actual == expected else AbstractTypeChecker.isSubtypeOf(typeContext, actual, expected)
    }
}

/** Before status resolution, an unspecified visibility is [Visibilities.Unknown], which means public here. */
private fun org.jetbrains.kotlin.descriptors.Visibility.isPublicOrDefault(): Boolean =
    this == Visibilities.Public || this == Visibilities.Unknown
