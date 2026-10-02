package com.example

import com.example.readme.examples
import kotlin.test.Test
import kotlin.test.assertEquals

/** The examples in the README's feature list, so the README can't drift from what the plugin does. */
class ReadmeExamplesTest {
    @Test
    fun `every example in the README works`() {
        assertEquals(listOf<Any>(6, 62370, 1, "pen=#pen", 13, 2, 6, 2, true, false), examples())
    }
}
