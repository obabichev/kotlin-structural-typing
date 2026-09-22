package com.example

import com.example.shapes.Sized
import com.example.shapes.area
import com.example.shapes.total
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `Sized` is declared in `:sample-library`, not here. That module publishes it in its index, so classes of this module
 * implement it by shape without knowing about it.
 */
class OtherModulesTest {
    class Photo(val width: Int, val height: Int)

    enum class Paper(val width: Int, val height: Int) {
        A4(210, 297),
    }

    @Test
    fun `a class implements an interface declared in another module`() {
        assertEquals(6, area(Photo(2, 3)))

        val sized: Sized = Photo(2, 3)
        assertEquals(2, sized.width)

        val anything: Any = Photo(2, 3)
        assertTrue(anything is Sized)
    }

    @Test
    fun `classes of both modules mix in one collection`() {
        assertEquals(62376, total(listOf(Photo(2, 3), Paper.A4)))
    }
}
