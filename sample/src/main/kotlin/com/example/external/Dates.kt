package com.example.external

import com.example.shapes.Dated
import com.example.shapes.iso
import kotlinx.datetime.LocalDate

/**
 * `kotlinx.datetime.LocalDate` is a class from a library on the classpath, written with no knowledge of
 * `com.example.shapes.Dated`, and its class file cannot be changed. The plugin gives it the interface as the compiler
 * reads that file, which is what makes this module compile.
 *
 * Compiling is only half of it: the call below becomes a cast, and the cast holds only if the interface is really on
 * the class when it loads. `structural-runtime` puts it there; `ExternalLibraryTest` shows both halves.
 */
fun release(): LocalDate = LocalDate(2026, 10, 2)

fun releaseIso(): String = iso(release())

fun dates(): List<Dated> = listOf(LocalDate(2026, 10, 2), LocalDate(2025, 1, 1))
