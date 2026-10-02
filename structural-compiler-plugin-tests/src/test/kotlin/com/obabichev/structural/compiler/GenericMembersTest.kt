package com.obabichev.structural.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A requirement may be generic itself. `fun <T> map(value: T): T` is matched by a member declaring as many type
 * parameters with the same bounds, the rest of the signature equal once the names are lined up -- which is what Kotlin
 * asks of a hand-written override.
 */
class GenericMembersTest {
    private val mapper = kotlin(
        "Mapper.kt",
        """
        package test
        import com.obabichev.structural.Structural
        @Structural interface Mapper { fun <T> map(value: T): T }
        fun useMapper(mapper: Mapper): String = mapper.map("a")
        """,
    )

    @Test
    fun `a generic member is matched, whatever its parameter is called`() {
        val compiled = compile(
            mapper,
            kotlin(
                "Main.kt",
                """
                package test
                class Identity { fun <R> map(value: R): R = value }
                fun run() = useMapper(Identity())
                """,
            ),
        )
        assertEquals("a", compiled.run())
    }

    @Test
    fun `a member that is not generic does not match a generic requirement`() {
        val compiled = compile(
            mapper,
            kotlin("Main.kt", "package test\nclass Strings { fun map(value: String): String = value }\nfun run() = useMapper(Strings())"),
        )
        assertFalse(compiled.succeeded, "a plain member can't implement a generic one")
    }

    @Test
    fun `a generic member does not match a plain requirement`() {
        val compiled = compile(
            SIZED,
            kotlin(
                "Main.kt",
                """
                package test
                class Odd { val width: Int = 1
                    val height: Int = 2
                    fun <T> extra(value: T): T = value }
                fun run() = size(Odd())
                """,
            ),
        )
        assertEquals(2, compiled.run(), "the generic member is simply not a requirement of Sized")
    }

    @Test
    fun `bounds have to agree`() {
        val bounded = kotlin(
            "Bounded.kt",
            """
            package test
            import com.obabichev.structural.Structural
            @Structural interface Numbers { fun <T : Number> sum(value: T): T }
            fun useNumbers(numbers: Numbers): Int = numbers.sum(1)
            """,
        )
        val matching = compile(
            bounded,
            kotlin("Main.kt", "package test\nclass Adder { fun <R : Number> sum(value: R): R = value }\nfun run() = useNumbers(Adder())"),
        )
        assertEquals(1, matching.run())

        val wrongBound = compile(
            bounded,
            kotlin("Main.kt", "package test\nclass Loose { fun <R> sum(value: R): R = value }\nfun run() = useNumbers(Loose())"),
        )
        assertFalse(wrongBound.succeeded, "an unbounded parameter must not satisfy T : Number")
    }

    @Test
    fun `the number of type parameters has to match`() {
        val compiled = compile(
            mapper,
            kotlin(
                "Main.kt",
                """
                package test
                class TwoParams { fun <A, B> map(value: A): A = value }
                fun run() = useMapper(TwoParams())
                """,
            ),
        )
        assertFalse(compiled.succeeded)
    }
}
