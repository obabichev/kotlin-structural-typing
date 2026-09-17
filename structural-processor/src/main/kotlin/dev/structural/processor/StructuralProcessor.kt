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

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (processed) return emptyList()
        processed = true

        val config = Config.parse(environment.options)
        if (config == null) {
            logger.error(
                "[structural] Missing KSP option '${Config.PACKAGES_OPTION}': " +
                    "set it to a comma-separated list of packages to scan",
            )
            return emptyList()
        }

        val interfaces = InterfaceRegistry(logger)
        resolver.getSymbolsWithAnnotation(STRUCTURAL_ANNOTATION).filterIsInstance<KSClassDeclaration>().forEach { interfaces.get(it) }

        val files = resolver.getAllFiles().toList()
        val groups = FunctionCollector(interfaces, logger).collect(files)
        val candidates = Candidates.find(files, config)
        val generator = Generator(environment.codeGenerator, Dependencies(aggregating = true, *files.toTypedArray()))
        groups.forEach { plan(it, candidates, generator) }
        generator.write()
        return emptyList()
    }

    private fun plan(group: FunctionGroup, candidates: List<KSClassDeclaration>, generator: Generator) {
        val functions = group.functions.associateBy { it.iface.name }
        val interfaces = group.functions.map { it.iface }
        val classes = (candidates + group.declaredTypes).associateBy { it.fqName }
        val input = PlanInput(
            declaredTypes = group.declaredTypes.map { it.fqName },
            structuralTypes = functions.keys,
            candidates = candidates.map { candidate ->
                val matches = interfaces.filter { Matcher.matchesStructurally(candidate, it) }.map { it.name }.toSet()
                PlanCandidate(candidate.fqName, matches)
            },
            supertypes = classes.mapValues { it.value.allSupertypeNames() },
            membersFirst = group.memberScope,
        )
        for (decision in OverloadPlanner.plan(input)) {
            when (decision) {
                is GenerateOverload ->
                    generator.overload(functions.getValue(decision.iface), classes.getValue(decision.candidate), decision.nominal)
                is AmbiguousCandidate -> logger.warn(
                    "[structural] Not generating ${group.name} for ${decision.candidate}: " +
                        "it matches several @Structural interfaces (${decision.ifaces.joinToString()})",
                    classes[decision.candidate],
                )
            }
        }
    }
}
