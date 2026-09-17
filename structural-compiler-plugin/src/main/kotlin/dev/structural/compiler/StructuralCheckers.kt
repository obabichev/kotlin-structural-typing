@file:OptIn(DirectDeclarationsAccess::class)

package dev.structural.compiler

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.resolve.lookupSuperTypes
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneTypeOrNull

class StructuralCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override fun FirDeclarationPredicateRegistrar.registerPredicates() {
        register(STRUCTURAL_PREDICATE)
    }

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val regularClassCheckers: Set<FirDeclarationChecker<FirRegularClass>> = setOf(InferredPropertyTypesChecker)
    }
}

/**
 * Warns about classes that would match a @Structural interface if their properties had explicit types: supertypes are
 * decided before inferred types are known, so such classes silently don't implement the interface.
 */
object InferredPropertyTypesChecker : FirDeclarationChecker<FirRegularClass>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirRegularClass) {
        if (declaration.classKind !in MATCHABLE_CLASS_KINDS) return
        val session = context.session
        val supertypes = lookupSuperTypes(declaration.symbol, lookupInterfaces = true, deep = true, useSiteSession = session)
            .mapNotNull { it.classId }
            .toSet()
        val ownProperties = declaration.declarations.filterIsInstance<FirProperty>()
        val inheritedProperties = session.superclassProperties(declaration.superTypeRefs)
        val properties = (inheritedProperties + ownProperties).associateBy { it.name }

        for (iface in session.structuralInterfaces()) {
            if (iface.classId in supertypes) continue
            if (!session.matches(ownProperties, inheritedProperties, iface, PropertyTypes { it.returnTypeRef.coneTypeOrNull })) continue
            val inferred = requiredProperties(iface)
                .mapNotNull { properties[it.name] }
                .filter { (it.returnTypeRef as? FirResolvedTypeRef)?.delegatedTypeRef == null }
                .map { it.name.asString() }
            if (inferred.isEmpty()) continue
            reporter.reportOn(
                declaration.source,
                StructuralDiagnostics.INFERRED_PROPERTY_TYPES,
                "'${declaration.name}' matches @Structural interface '${iface.classId.asFqNameString()}' but doesn't " +
                    "implement it, because these properties have inferred types: ${inferred.joinToString()}. " +
                    "Declare their types explicitly.",
            )
        }
    }
}
