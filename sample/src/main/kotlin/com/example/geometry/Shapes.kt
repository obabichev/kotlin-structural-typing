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

// Ordinary functions: any class with a matching shape is a Sized, Measurable or Counter.

fun size(target: Sized) = target.width * target.height

suspend fun sizeLater(target: Sized) = size(target)

fun String.label(target: Sized) = "$this ${target.width}x${target.height}"

fun Sized.area() = width * height

fun describeOrNone(target: Sized?) = target?.let { "${it.width}x${it.height}" } ?: "none"

fun totalSize(vararg targets: Sized) = targets.sumOf { size(it) }

fun totalArea(items: List<Sized>) = items.sumOf { size(it) }

fun <T : Sized> larger(a: T, b: T): T = if (size(a) >= size(b)) a else b

fun describe(target: Measurable, unit: String = "m") = "${target.length} $unit"

fun increment(target: Counter) {
    target.count++
}

class Canvas(val name: String) {
    fun fits(target: Sized) = target.width <= 100 && target.height <= 100

    companion object {
        fun sizedFor(target: Sized) = Canvas("${target.width}x${target.height}")
    }
}
