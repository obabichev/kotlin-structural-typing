package com.example

import com.example.geometry.Canvas
import com.example.geometry.describe
import com.example.geometry.fits
import com.example.geometry.increment
import com.example.geometry.label
import com.example.geometry.size
import com.example.geometry.sizeLater
import com.example.model.Clicks
import com.example.model.PictureFrame
import com.example.model.Rectangular
import com.example.model.Rope
import com.example.model.Window
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SampleTest {
    @Test
    fun `original example`() {
        assertEquals(2, size(Rectangular(1, 2, "red")))
    }

    @Test
    fun `member function through extension overload`() {
        assertTrue(Canvas("main").fits(Rectangular(10, 10, "red")))
    }

    @Test
    fun `extension function`() {
        assertEquals("box 3x4", "box".label(Rectangular(3, 4, "blue")))
    }

    @Test
    fun `Int satisfies Number`() {
        assertEquals("5 m", describe(Rope(5), "m"))
    }

    @Test
    fun `var writes through to the original object`() {
        val clicks = Clicks(1)
        increment(clicks)
        assertEquals(2, clicks.count)
    }

    @Test
    fun `suspend function`() {
        var result = 0
        val block: suspend () -> Int = { sizeLater(Rectangular(2, 5, "green")) }
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it.getOrThrow() })
        assertEquals(10, result)
    }

    @Test
    fun `subclass uses the base class overload`() {
        assertEquals(200, size(PictureFrame("oak")))
    }

    @Test
    fun `nominal subclass of a structural match`() {
        assertEquals(1200, size(Window()))
    }
}
