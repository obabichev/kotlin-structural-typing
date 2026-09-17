package dev.structural.processor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigTest {
    @Test
    fun `parses comma-separated packages`() {
        val config = Config.parse(mapOf(Config.PACKAGES_OPTION to " a.b, c ,,"))
        assertEquals(listOf("a.b", "c"), config.packages)
    }

    @Test
    fun `missing or blank option includes every package`() {
        for (options in listOf(emptyMap(), mapOf(Config.PACKAGES_OPTION to " , "))) {
            val config = Config.parse(options)
            assertEquals(emptyList(), config.packages)
            assertTrue(config.includesPackage("any.package"))
        }
    }

    @Test
    fun `includes subpackages but not name prefixes`() {
        val config = Config(listOf("a.b"))
        assertTrue(config.includesPackage("a.b"))
        assertTrue(config.includesPackage("a.b.c"))
        assertFalse(config.includesPackage("a.bc"))
        assertFalse(config.includesPackage("a"))
    }
}
