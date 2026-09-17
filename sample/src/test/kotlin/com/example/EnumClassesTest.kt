package com.example

import com.example.geometry.size
import com.example.model.Paper
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Enum classes match like other classes.
 */
class EnumClassesTest {
    @Test
    fun `enum classes`() {
        assertEquals(listOf(62370, 31080), Paper.entries.map { size(it) })
    }
}
