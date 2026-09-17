package dev.structural.processor

/** A class from the scanned packages and the group's @Structural interfaces it matches by shape (not nominally). */
data class PlanCandidate(val name: String, val structuralMatches: Set<String>)

class PlanInput(
    /** Types of the structural parameter position in user-declared overloads of the group. */
    val declaredTypes: List<String>,
    /** The group's @Structural interfaces. */
    val structuralTypes: Set<String>,
    val candidates: List<PlanCandidate>,
    /** Transitive proper supertypes of every candidate and declared type. */
    val supertypes: Map<String, Set<String>>,
    /** True for member functions: Kotlin picks an applicable member before any extension. */
    val membersFirst: Boolean,
)

sealed interface Decision {
    val candidate: String
}

data class Generate(override val candidate: String, val iface: String, val nominal: Boolean) : Decision

data class Ambiguous(override val candidate: String, val ifaces: Set<String>) : Decision

/**
 * Decides which overloads (for a function group) or adapters (for an interface) to generate, so that the generated
 * declarations never make a call ambiguous. See "Overload resolution safety" in the proposal.
 */
object OverloadPlanner {
    fun plan(input: PlanInput): List<Decision> {
        fun supertypesOf(name: String) = input.supertypes[name].orEmpty()

        val planned = mutableListOf<String>()
        val decisions = mutableListOf<Decision>()
        // A subtype always has more supertypes than its supertypes, so this visits supertypes first.
        for (candidate in input.candidates.sortedBy { supertypesOf(it.name).size }) {
            if (candidate.name in input.declaredTypes) continue
            val supertypes = supertypesOf(candidate.name)
            val declaredApplicable = input.declaredTypes.filter { it in supertypes }
            if (input.membersFirst && declaredApplicable.isNotEmpty()) continue

            val applicable = (declaredApplicable + planned.filter { it in supertypes }).distinct()
            val hasMostSpecific = applicable.any { type -> applicable.all { it == type || it in supertypesOf(type) } }
            if (hasMostSpecific) continue

            val interfaces = (input.structuralTypes.filter { it in supertypes } + candidate.structuralMatches).toSortedSet()
            when (interfaces.size) {
                0 -> Unit
                1 -> {
                    val iface = interfaces.single()
                    decisions += Generate(candidate.name, iface, nominal = iface in supertypes)
                    planned += candidate.name
                }
                else -> decisions += Ambiguous(candidate.name, interfaces)
            }
        }
        return decisions
    }
}
