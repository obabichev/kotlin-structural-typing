package com.example

import com.example.readme.Page
import com.example.readme.Shape
import com.example.readme.loadFrom
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import com.example.readme.examples
import kotlin.test.Test
import kotlin.test.assertEquals

/** The examples in the README's feature list, so the README can't drift from what the plugin does. */
class ReadmeExamplesTest {
    @Test
    fun `every example in the README works`() {
        assertEquals(
            listOf<Any>(6, 62370, 1, "pen=#pen", 13, 2, 6, 2, true, false, Shape.Kind.ROUND, "== p ==", "a, b", "== s =="),
            examples(),
        )
    }

    /** A suspend requirement is matched, and the interface is usable from a coroutine. */
    @Test
    fun `a suspend requirement works`() {
        assertEquals("p", runSuspending { loadFrom(Page("p")) })
    }

    /** Enough of a coroutine runner for a suspend function that never actually suspends; no dependency needed. */
    private fun <T> runSuspending(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        return checkNotNull(outcome) { "the block suspended, which these examples never do" }.getOrThrow()
    }
}
