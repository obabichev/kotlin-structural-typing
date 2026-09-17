package com.example

import com.example.geometry.size
import com.example.model.PictureFrame
import com.example.model.Window
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Subclasses of a matching class implement the interface through it; classes that declare the interface themselves are
 * used as usual.
 */
class InheritanceTest {
    @Test
    fun `subclasses and nominal implementors`() {
        assertEquals(200, size(PictureFrame("oak")))
        assertEquals(1200, size(Window()))
    }
}
