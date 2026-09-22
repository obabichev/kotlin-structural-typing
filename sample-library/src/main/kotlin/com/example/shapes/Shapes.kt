package com.example.shapes

import com.obabichev.structural.Structural

/**
 * A @Structural interface published by a library module. Because this module is compiled with the plugin, it writes the
 * interface to META-INF/structural/interfaces.txt, so classes in modules depending on it can implement it by shape.
 */
@Structural
interface Sized {
    val width: Int
    val height: Int
}

fun area(target: Sized): Int = target.width * target.height

fun total(targets: List<Sized>): Int = targets.sumOf { area(it) }
