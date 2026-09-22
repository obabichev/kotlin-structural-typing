package com.obabichev.structural.compiler

import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.diagnostics.FirDiagnosticHolder
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.resolve.calls.AbstractCallCandidate
import org.jetbrains.kotlin.fir.resolve.calls.ArgumentTypeMismatch
import org.jetbrains.kotlin.fir.resolve.diagnostics.ConeDiagnosticWithCandidates
import org.jetbrains.kotlin.fir.resolve.lookupSuperTypes
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.name.ClassId

class StructuralCheckers(
    session: FirSession,
    imported: List<ClassId> = emptyList(),
) : FirAdditionalCheckersExtension(session) {
    private val index = StructuralIndex(session, imported)

    override fun FirDeclarationPredicateRegistrar.registerPredicates() {
        register(STRUCTURAL_PREDICATE)
    }

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val regularClassCheckers: Set<FirDeclarationChecker<FirRegularClass>> = setOf(NearMissChecker(index))
    }

    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers: Set<FirExpressionChecker<FirFunctionCall>> =
            setOf(ArgumentNearMissChecker(index))
    }
}

/** The @Structural interfaces of the module, collected once per session rather than per declaration or call. */
internal class StructuralIndex(val session: FirSession, private val imported: List<ClassId> = emptyList()) {
    val types = ResolvedTypes(session)
    val interfaces: List<StructuralInterface> by lazy { session.structuralInterfaces(types, imported) }

    fun byClassId(classId: ClassId): StructuralInterface? = interfaces.firstOrNull { it.classId == classId }

    fun supertypesOf(klass: FirRegularClassSymbol): Set<ClassId> =
        lookupSuperTypes(klass, lookupInterfaces = true, deep = true, useSiteSession = session)
            .mapNotNull { it.classId }
            .toSet()

    fun membersOf(klass: FirRegularClassSymbol): List<Member> =
        session.classMembers(klass, types.superTypes(klass), types)
}

/**
 * Explains an argument the compiler rejects: where a @Structural interface is expected and the argument's class doesn't
 * implement it, says which requirements it fails. The compiler's own `Argument type mismatch` names the two types and
 * nothing else, so there is no sign that structural matching was attempted, let alone what to change.
 *
 * Reported as an error rather than a warning because the compiler prints no warnings at all once a compilation has an
 * error. It only fires where resolution already failed, so it can't fail a build that would otherwise succeed.
 *
 * No threshold is needed here, unlike [NearMissChecker]: the interface was named at that position, so the comparison is
 * the one the code asked for.
 */
internal class ArgumentNearMissChecker(private val index: StructuralIndex) :
    FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val reference = expression.calleeReference as? FirDiagnosticHolder ?: return
        val candidates = (reference.diagnostic as? ConeDiagnosticWithCandidates)?.candidates ?: return
        // Overloads can reject the same argument for the same reason; report each argument once.
        val reported = mutableSetOf<Pair<KtSourceElement?, ClassId>>()

        for (candidate in candidates.filterIsInstance<AbstractCallCandidate<*>>()) {
            for (rejected in candidate.diagnostics.filterIsInstance<ArgumentTypeMismatch>()) {
                val expected = rejected.expectedType.classId ?: continue
                val iface = index.byClassId(expected) ?: continue
                val klass = rejected.actualType.toRegularClassSymbol(index.session) ?: continue
                if (klass.classKind !in MATCHABLE_CLASS_KINDS) continue
                if (!reported.add(rejected.argument.source to expected)) continue

                val reasons = index.session.explain(index.membersOf(klass), iface, index.types)
                if (reasons.isEmpty()) continue
                reporter.reportOn(
                    rejected.argument.source,
                    StructuralDiagnostics.ARGUMENT_NEAR_MISS,
                    "'${klass.name}' does not implement @Structural interface '${iface.classId.asFqNameString()}':\n" +
                        reasons.joinToString("\n") { "    ${it.render()}" },
                )
            }
        }
    }
}

/**
 * Warns about a class that comes close to a @Structural interface but doesn't implement it. Two kinds of near miss:
 *
 * - every requirement is met now, but the members' types are inferred, and supertypes are decided before inferred types
 *   are known, so the class silently didn't match;
 * - every required member is present but some detail is wrong (a type, a parameter name, visibility).
 *
 * The second kind needs a threshold: without one every class in the module would be reported against every interface.
 * A class qualifies only when it has all the required names and satisfies at least one requirement, so an interface with
 * a single requirement is never reported here — [ArgumentNearMissChecker] covers those at the call site.
 */
internal class NearMissChecker(private val index: StructuralIndex) :
    FirDeclarationChecker<FirRegularClass>(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirRegularClass) {
        if (declaration.classKind !in MATCHABLE_CLASS_KINDS) return
        val session = index.session
        val types = index.types
        val supertypes = index.supertypesOf(declaration.symbol)
        val members = index.membersOf(declaration.symbol)

        for (iface in index.interfaces) {
            if (iface.classId in supertypes) continue
            val reasons = session.explain(members, iface, types)
            if (reasons.isEmpty()) {
                reportInferredTypes(declaration, iface, members, types)
                continue
            }
            if (reasons.any { it is Mismatch.NoSuchMember } || reasons.size >= iface.requirements.size) continue
            reporter.reportOn(
                declaration.source,
                StructuralDiagnostics.NEAR_MISS,
                "'${declaration.name}' almost implements @Structural interface '${iface.classId.asFqNameString()}':\n" +
                    reasons.joinToString("\n") { "    ${it.render()}" },
            )
        }
    }

    /**
     * Reached when the class matches with resolved types although it didn't gain the interface, which means the members
     * that would satisfy it have inferred types: those aren't known when supertypes are decided.
     */
    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun reportInferredTypes(
        declaration: FirRegularClass,
        iface: StructuralInterface,
        members: List<Member>,
        types: TypeLookup,
    ) {
        val inferred = iface.requirements
            .flatMap { requirement -> members.filter { index.session.satisfies(it, requirement, types) } }
            .filter { (it.declaration.returnTypeRef as? FirResolvedTypeRef)?.delegatedTypeRef == null }
            .mapNotNull { it.name?.asString() }
            .distinct()
        if (inferred.isEmpty()) return
        reporter.reportOn(
            declaration.source,
            StructuralDiagnostics.INFERRED_MEMBER_TYPES,
            "'${declaration.name}' matches @Structural interface '${iface.classId.asFqNameString()}' but doesn't " +
                "implement it, because these members have inferred types: ${inferred.joinToString()}. " +
                "Declare their types explicitly.",
        )
    }
}
