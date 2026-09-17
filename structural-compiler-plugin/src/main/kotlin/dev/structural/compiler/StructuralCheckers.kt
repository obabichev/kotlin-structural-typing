package dev.structural.compiler

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.resolve.lookupSuperTypes
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.classId

class StructuralCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override fun FirDeclarationPredicateRegistrar.registerPredicates() {
        register(STRUCTURAL_PREDICATE)
    }

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val regularClassCheckers: Set<FirDeclarationChecker<FirRegularClass>> = setOf(InferredMemberTypesChecker)
    }
}

/**
 * Warns about classes that would match a @Structural interface if their properties and functions had explicit types:
 * supertypes are decided before inferred types are known, so such classes silently don't implement the interface.
 */
object InferredMemberTypesChecker : FirDeclarationChecker<FirRegularClass>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirRegularClass) {
        if (declaration.classKind !in MATCHABLE_CLASS_KINDS) return
        val session = context.session
        val types = ResolvedTypes(session)
        val supertypes = lookupSuperTypes(declaration.symbol, lookupInterfaces = true, deep = true, useSiteSession = session)
            .mapNotNull { it.classId }
            .toSet()
        val members = session.classMembers(declaration.symbol, declaration.superTypeRefs, types)

        for (iface in session.structuralInterfaces(types)) {
            if (iface.classId in supertypes || !session.implementsByShape(members, iface, types)) continue
            val inferred = iface.requirements
                .flatMap { requirement -> members.filter { session.satisfies(it, requirement, types) } }
                .filter { (it.declaration.returnTypeRef as? FirResolvedTypeRef)?.delegatedTypeRef == null }
                .mapNotNull { it.name?.asString() }
                .distinct()
            if (inferred.isEmpty()) continue
            reporter.reportOn(
                declaration.source,
                StructuralDiagnostics.INFERRED_MEMBER_TYPES,
                "'${declaration.name}' matches @Structural interface '${iface.classId.asFqNameString()}' but doesn't " +
                    "implement it, because these members have inferred types: ${inferred.joinToString()}. " +
                    "Declare their types explicitly.",
            )
        }
    }
}
