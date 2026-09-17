package com.example.model

import com.example.geometry.Sized

class Rectangular(val width: Int, val height: Int, val color: String)

class Rope(val length: Int)

class Clicks(var count: Int)

open class Frame(val width: Int, val height: Int)

class PictureFrame(val material: String) : Frame(10, 20)

class Window : Frame(30, 40), Sized

enum class Paper(val width: Int, val height: Int) {
    A4(210, 297),
    A5(148, 210),
}

class Product(val name: String, val price: Int) {
    fun label(): String = "$name for $price"
}

/** Matches Labeled through the built-in `name` of enum classes. */
enum class Priority {
    LOW,
    HIGH;

    fun label(): String = name.lowercase()
}
