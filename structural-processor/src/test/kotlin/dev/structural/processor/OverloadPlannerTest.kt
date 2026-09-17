package dev.structural.processor

import kotlin.test.Test
import kotlin.test.assertEquals

class OverloadPlannerTest {
    private val any = "kotlin.Any"

    private fun plan(
        candidates: List<PlanCandidate>,
        supertypes: Map<String, Set<String>>,
        declared: List<String> = listOf("Sized"),
        structural: Set<String> = setOf("Sized"),
        membersFirst: Boolean = false,
    ): List<Decision> = OverloadPlanner.plan(
        PlanInput(
            declaredTypes = declared,
            structuralTypes = structural,
            candidates = candidates,
            supertypes = supertypes + mapOf("Sized" to setOf(any), "Named" to setOf(any)),
            membersFirst = membersFirst,
        ),
    )

    @Test
    fun `structural match gets a proxy overload`() {
        val decisions = plan(listOf(PlanCandidate("Rect", setOf("Sized"))), mapOf("Rect" to setOf(any)))
        assertEquals(listOf(GenerateOverload("Rect", "Sized", nominal = false)), decisions)
    }

    @Test
    fun `classes that do not match get nothing`() {
        assertEquals(emptyList(), plan(listOf(PlanCandidate("Circle", emptySet())), mapOf("Circle" to setOf(any))))
    }

    @Test
    fun `nominal implementors use the original function`() {
        assertEquals(emptyList(), plan(listOf(PlanCandidate("Impl", emptySet())), mapOf("Impl" to setOf("Sized", any))))
    }

    @Test
    fun `subclasses reuse the base class overload regardless of input order`() {
        val decisions = plan(
            candidates = listOf(PlanCandidate("Mid", setOf("Sized")), PlanCandidate("Base", setOf("Sized"))),
            supertypes = mapOf("Mid" to setOf("Base", any), "Base" to setOf(any)),
        )
        assertEquals(listOf(GenerateOverload("Base", "Sized", nominal = false)), decisions)
    }

    @Test
    fun `nominal subclass of a matched class gets a direct overload`() {
        val decisions = plan(
            candidates = listOf(PlanCandidate("Base", setOf("Sized")), PlanCandidate("Child", emptySet())),
            supertypes = mapOf("Base" to setOf(any), "Child" to setOf("Base", "Sized", any)),
        )
        assertEquals(
            listOf(GenerateOverload("Base", "Sized", nominal = false), GenerateOverload("Child", "Sized", nominal = true)),
            decisions,
        )
    }

    @Test
    fun `class matching several interfaces is ambiguous`() {
        val decisions = plan(
            candidates = listOf(PlanCandidate("Both", setOf("Sized", "Named"))),
            supertypes = mapOf("Both" to setOf(any)),
            declared = listOf("Sized", "Named"),
            structural = setOf("Sized", "Named"),
        )
        assertEquals(listOf(AmbiguousCandidate("Both", setOf("Named", "Sized"))), decisions)
    }

    @Test
    fun `hand-written overloads are respected`() {
        val decisions = plan(
            candidates = listOf(PlanCandidate("Rect", setOf("Sized")), PlanCandidate("Square", setOf("Sized"))),
            supertypes = mapOf("Rect" to setOf(any), "Square" to setOf("Rect", any)),
            declared = listOf("Sized", "Rect"),
        )
        assertEquals(emptyList(), decisions)
    }

    @Test
    fun `members win over generated extensions`() {
        val decisions = plan(
            candidates = listOf(PlanCandidate("Child", setOf("Sized")), PlanCandidate("Rect", setOf("Sized"))),
            supertypes = mapOf("Child" to setOf("Base", any), "Rect" to setOf(any), "Base" to setOf(any)),
            declared = listOf("Sized", "Base"),
            membersFirst = true,
        )
        assertEquals(listOf(GenerateOverload("Rect", "Sized", nominal = false)), decisions)
    }
}
