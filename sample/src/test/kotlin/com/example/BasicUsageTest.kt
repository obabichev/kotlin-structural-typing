package com.example

import com.example.geometry.Sized
import com.example.geometry.size
import com.example.model.Rectangular
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Classes with the shape of `Sized` can be passed where `Sized` is expected, and really implement it, without any
 * wrapper.
 */
class BasicUsageTest {
    @Test
    fun `original example`() {
        assertEquals(2, size(Rectangular(1, 2, "red")))
    }

    @Test
    fun `the object itself is passed, not a wrapper`() {
        val rect = Rectangular(1, 1, "red")
        val sized: Sized = rect
        assertSame(rect, sized)
    }
}
