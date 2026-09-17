package dev.structural.processor

import kotlin.test.Test
import kotlin.test.assertEquals

class FunctionGroupsTest {
    /** Runs [describe] inside the KSP round: symbols can't be used after it ends. */
    private fun <T> collect(code: String, describe: (FunctionCollection) -> T): T {
        val source = kotlin(
            "Functions.kt",
            """
            package test
            import dev.structural.Structural
            @Structural interface Sized { val width: Int }
            @Structural interface Named { val name: String }
            @Structural interface Unused { val id: Int }
            """ + code,
        )
        return resolve(source) { resolver, logger ->
            describe(FunctionCollector(InterfaceRegistry(logger), logger).collect(resolver.getAllFiles().toList()))
        }.first
    }

    @Test
    fun `groups overloads that differ only in the structural parameter`() {
        val groups = collect(
            """
            open class Base
            fun describe(target: Sized, prefix: String) = ""
            fun describe(target: Named, prefix: String) = ""
            fun describe(target: Base, prefix: String) = ""
            fun describe(target: Sized, prefix: Int) = ""
            class Owner { fun describe(target: Sized, prefix: String) = "" }
            """,
        ) { collection ->
            collection.groups.map { group ->
                val scope = if (group.memberScope) "member" else "top"
                val interfaces = group.functions.map { it.iface.name }.sorted()
                val declared = group.declaredTypes.map { it.fqName }.sorted()
                "$scope ${group.name}: $interfaces declared $declared"
            }.sorted()
        }

        assertEquals(
            listOf(
                "member test.Owner.describe: [test.Sized] declared [test.Sized]",
                "top test.describe: [test.Named, test.Sized] declared [test.Base, test.Named, test.Sized]",
                "top test.describe: [test.Sized] declared [test.Sized]",
            ),
            groups,
        )
    }

    @Test
    fun `groups extension functions by structural receiver`() {
        val groups = collect(
            """
            open class Base
            fun Sized.area(scale: Int) = 0
            fun Named.area(scale: Int) = 0
            fun Base.area(scale: Int) = 0
            """,
        ) { collection ->
            collection.groups.map { group ->
                val slots = group.functions.map { it.slot }.distinct()
                val interfaces = group.functions.map { it.iface.name }.sorted()
                "${group.name} $slots: $interfaces declared ${group.declaredTypes.map { it.fqName }.sorted()}"
            }
        }
        assertEquals(
            listOf("test.area [${StructuralFunction.RECEIVER}]: [test.Named, test.Sized] declared [test.Base, test.Named, test.Sized]"),
            groups,
        )
    }

    @Test
    fun `records interfaces referenced by any function`() {
        val referenced = collect(
            """
            fun total(items: List<Named>) = 0
            fun size(target: Sized) = 0
            """,
        ) { collection -> collection.referencedInterfaces.map { it.name }.sorted() }
        assertEquals(listOf("test.Named", "test.Sized"), referenced)
    }
}
