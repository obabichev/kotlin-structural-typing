package com.example

import com.example.geometry.describeAll
import com.example.model.Priority
import com.example.model.Product
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `Labeled` requires the function `label()` and, through its superinterface `Named`, the property `name`. A class and an
 * enum class (whose built-in `name` counts) both match.
 */
class FunctionsAndSuperinterfacesTest {
    @Test
    fun `functions and superinterface members`() {
        assertEquals("tea=tea for 3, HIGH=high", describeAll(listOf(Product("tea", 3), Priority.HIGH)))
    }
}
