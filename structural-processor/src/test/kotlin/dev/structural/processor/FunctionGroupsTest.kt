package dev.structural.processor

import kotlin.test.Test
import kotlin.test.assertEquals

class FunctionGroupsTest {
    @Test
    fun `groups overloads that differ only in the structural parameter`() {
        val source = kotlin(
            "Functions.kt",
            """
            package test
            import dev.structural.Structural
            @Structural interface Sized { val width: Int }
            @Structural interface Named { val name: String }
            open class Base
            fun describe(target: Sized, prefix: String) = ""
            fun describe(target: Named, prefix: String) = ""
            fun describe(target: Base, prefix: String) = ""
            fun describe(target: Sized, prefix: Int) = ""
            class Owner { fun describe(target: Sized, prefix: String) = "" }
            """,
        )

        val (groups, _) = resolve(source) { resolver, logger ->
            FunctionCollector(InterfaceRegistry(logger), logger).collect(resolver.getAllFiles().toList()).map { group ->
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
}
