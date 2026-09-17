package com.example

import com.example.geometry.Canvas
import com.example.geometry.area
import com.example.geometry.describe
import com.example.geometry.describeOrNone
import com.example.geometry.label
import com.example.geometry.larger
import com.example.geometry.sizeLater
import com.example.geometry.totalArea
import com.example.geometry.totalSize
import com.example.model.PictureFrame
import com.example.model.Rectangular
import com.example.model.Rope
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Because matching classes really implement the interface, every kind of function accepts them: members, companion
 * objects, extensions, nullable and vararg parameters, collections, generics, default parameters and suspend functions.
 */
class CallSitesTest {
    @Test
    fun `member and companion object functions`() {
        assertTrue(Canvas("main").fits(Rectangular(10, 10, "red")))
        assertEquals("4x5", Canvas.sizedFor(Rectangular(4, 5, "red")).name)
    }

    @Test
    fun `extension functions`() {
        assertEquals("box 3x4", "box".label(Rectangular(3, 4, "blue")))
        assertEquals(6, Rectangular(2, 3, "red").area())
    }

    @Test
    fun `nullable values`() {
        val maybe: Rectangular? = Rectangular(2, 3, "red")
        assertEquals("2x3none", describeOrNone(maybe) + describeOrNone(null))
    }

    @Test
    fun `vararg with different classes`() {
        assertEquals(202, totalSize(Rectangular(1, 2, "red"), PictureFrame("oak")))
    }

    @Test
    fun `collections and generics`() {
        val rectangles: List<Rectangular> = listOf(Rectangular(1, 2, "red"), Rectangular(3, 4, "blue"))
        assertEquals(14, totalArea(rectangles))
        assertEquals("blue", larger(rectangles[0], rectangles[1]).color)
    }

    @Test
    fun `default parameter and Int for Number`() {
        assertEquals("5 m", describe(Rope(5)))
    }

    @Test
    fun `suspend function`() {
        var result = 0
        val block: suspend () -> Int = { sizeLater(Rectangular(2, 5, "green")) }
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it.getOrThrow() })
        assertEquals(10, result)
    }
}
