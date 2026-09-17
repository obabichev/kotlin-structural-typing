package dev.structural.processor

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigTest {
    @Test
    fun `parses comma-separated packages`() {
        val config = Config.parse(mapOf(Config.PACKAGES_OPTION to " a.b, c ,,"))
        assertEquals(listOf("a.b", "c"), config?.packages)
    }

    @Test
    fun `missing or blank option gives null`() {
        assertNull(Config.parse(emptyMap()))
        assertNull(Config.parse(mapOf(Config.PACKAGES_OPTION to " , ")))
    }

    @Test
    fun `includes subpackages but not name prefixes`() {
        val config = Config(listOf("a.b"))
        assertTrue(config.includesPackage("a.b"))
        assertTrue(config.includesPackage("a.b.c"))
        assertFalse(config.includesPackage("a.bc"))
        assertFalse(config.includesPackage("a"))
    }

    @Test
    fun `missing option fails the build`() {
        val compiled = compile(kotlin("A.kt", "package test\nclass A"), options = emptyMap())
        assertFalse(compiled.succeeded)
        assertContains(compiled.messages, "[structural] Missing KSP option 'structural.packages'")
    }
}
