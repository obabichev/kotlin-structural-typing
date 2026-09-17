package dev.structural.processor

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration

class StructuralProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = StructuralProcessor(environment)
}

class StructuralProcessor(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {
    private val logger = environment.logger
    private var processed = false
    private val matchCache = mutableMapOf<Pair<String, String>, Boolean>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (processed) return emptyList()
        processed = true

        val config = Config.parse(environment.options)

        val interfaces = InterfaceRegistry(logger)
        val declared = resolver.getSymbolsWithAnnotation(STRUCTURAL_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .mapNotNull { interfaces.get(it) }
            .toList()

        val files = resolver.getAllFiles().toList()
        val collection = FunctionCollector(interfaces, logger).collect(files)
        val candidates = Candidates.find(files, config)
        val generator = Generator(environment.codeGenerator, Dependencies(aggregating = true, *files.toTypedArray()))
        (declared + collection.referencedInterfaces).distinctBy { it.name }.forEach { planAdapters(it, candidates, generator) }
        collection.groups.forEach { planOverloads(it, candidates, generator) }
        generator.write()
        return emptyList()
    }

    /** Adapters behave like overloads of `asI()` on the receiver, so the same planner keeps them unambiguous. */
    private fun planAdapters(iface: StructuralInterface, candidates: List<KSClassDeclaration>, generator: Generator) {
        generator.identityAdapter(iface)
        val classes = (candidates + iface.declaration).associateBy { it.fqName }
        val input = PlanInput(
            declaredTypes = listOf(iface.name),
            structuralTypes = setOf(iface.name),
            candidates = candidates.map { candidate ->
                PlanCandidate(candidate.fqName, if (matches(candidate, iface)) setOf(iface.name) else emptySet())
            },
            supertypes = classes.mapValues { it.value.allSupertypeNames() },
            membersFirst = false,
        )
        OverloadPlanner.plan(input)
            .filterIsInstance<Generate>()
            .forEach { generator.adapter(iface, classes.getValue(it.candidate), it.nominal) }
    }

    private fun planOverloads(group: FunctionGroup, candidates: List<KSClassDeclaration>, generator: Generator) {
        val functions = group.functions.associateBy { it.iface.name }
        val interfaces = group.functions.map { it.iface }
        val classes = (candidates + group.declaredTypes).associateBy { it.fqName }
        val input = PlanInput(
            declaredTypes = group.declaredTypes.map { it.fqName },
            structuralTypes = functions.keys,
            candidates = candidates.map { candidate ->
                PlanCandidate(candidate.fqName, interfaces.filter { matches(candidate, it) }.map { it.name }.toSet())
            },
            supertypes = classes.mapValues { it.value.allSupertypeNames() },
            membersFirst = group.memberScope,
        )
        for (decision in OverloadPlanner.plan(input)) {
            when (decision) {
                is Generate -> generator.overload(functions.getValue(decision.iface), classes.getValue(decision.candidate))
                is Ambiguous -> logger.warn(
                    "[structural] Not generating ${group.name} for ${decision.candidate}: " +
                        "it matches several @Structural interfaces (${decision.ifaces.joinToString()})",
                    classes[decision.candidate],
                )
            }
        }
    }

    private fun matches(candidate: KSClassDeclaration, iface: StructuralInterface): Boolean =
        matchCache.getOrPut(candidate.fqName to iface.name) { Matcher.matchesStructurally(candidate, iface) }
}
