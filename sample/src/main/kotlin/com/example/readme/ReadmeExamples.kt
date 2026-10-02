package com.example.readme

import com.example.shapes.area as libraryArea
import com.obabichev.structural.Structural

@Structural
interface Sized {
    val width: Int
    val height: Int
    fun area(): Int
}

@Structural
interface Named {
    val name: String
}

@Structural
interface Labeled : Named {
    fun label(): String
}

@Structural
interface Ranked : Comparable<String>

@Structural
interface Counter {
    var count: Int
}

class Rectangular(val width: Int, val height: Int) {
    fun area(): Int = width * height
}

enum class Paper(val width: Int, val height: Int) {
    A4(210, 297);

    fun area(): Int = width * height
}

object Square {
    val width: Int = 1
    val height: Int = 1
    fun area(): Int = 1
}

/** A `val` cannot implement the `var` that `Counter` asks for. */
class Score(val count: Int)

class Product(val name: String) {
    fun label(): String = "#$name"
}

class Word(private val word: String) {
    operator fun compareTo(other: String): Int = word.compareTo(other)
}

class Clicks(var count: Int)

/** `com.example.shapes.Sized` is declared in another module; this class never mentions it. */
class Photo(val width: Int, val height: Int)

fun area(target: Sized): Int = target.area()
fun describe(target: Labeled): String = "${target.name}=${target.label()}"
fun rank(target: Ranked): Int = target.compareTo("m")
fun bump(target: Counter) { target.count++ }
fun photoArea(): Int = libraryArea(Photo(2, 3))

fun examples(): List<Any> = listOf(
    area(Rectangular(2, 3)),
    area(Paper.A4),
    area(Square),
    describe(Product("pen")),
    rank(Word("z")),
    Clicks(1).also { bump(it) }.count,
    photoArea(),
    listOf<Sized>(Rectangular(1, 1), Paper.A4).size,
    (Rectangular(1, 1) as Any) is Sized,
    (Score(1) as Any) is Counter,
)
