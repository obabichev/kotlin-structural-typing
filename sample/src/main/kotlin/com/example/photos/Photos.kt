package com.example.photos

/**
 * Classes matching `com.example.shapes.Sized`, which is declared in `:sample-library`. Top level and in main sources,
 * like the classes of the other features; `OtherModulesTest` also matches a nested one.
 */
class Photo(val width: Int, val height: Int)

enum class Paper(val width: Int, val height: Int) {
    A4(210, 297),
}
