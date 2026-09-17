package com.example

import com.example.geometry.increment
import com.example.model.Clicks
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A `var` requirement is implemented by the class's own `var`, so writes through the interface change the object.
 */
class PropertiesTest {
    @Test
    fun `var writes through to the object`() {
        val clicks = Clicks(1)
        increment(clicks)
        assertEquals(2, clicks.count)
    }
}
