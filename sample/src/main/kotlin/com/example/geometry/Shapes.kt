package com.example.geometry

import dev.structural.Structural

@Structural
interface Sized {
    val width: Int
    val height: Int
}

@Structural
interface Measurable {
    val length: Number
}

@Structural
interface Counter {
    var count: Int
}

fun size(target: Sized) = target.width * target.height

fun describe(target: Measurable, unit: String) = "${target.length} $unit"

fun increment(target: Counter) {
    target.count++
}

suspend fun sizeLater(target: Sized) = size(target)

fun String.label(target: Sized) = "$this ${target.width}x${target.height}"

class Canvas(val name: String) {
    fun fits(target: Sized) = target.width <= 100 && target.height <= 100
}
