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

@Structural
interface Report {
    val title: String

    /** A member with a body is not required of a class: only the abstract ones are. */
    fun headline(): String = "== $title =="

    suspend fun load(): String

    fun merge(vararg others: String): String
}

@Structural
interface Box<T> {
    val value: T
}

@Structural
interface Mapper {
    fun <T> map(value: T): T
}

@Structural
interface Shelf {
    val items: List<CharSequence>
}

@Structural
interface Shape {
    val kind: Kind

    enum class Kind { ROUND, FLAT }
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

class Disc(val kind: Shape.Kind)

class IntBox(val value: Int)

class Identity {
    fun <R> map(value: R): R = value
}

/** `List<out E>` is covariant, so a `List<String>` satisfies a `List<CharSequence>`. */
class Books(val items: List<String>)

class Page(val title: String) {
    suspend fun load(): String = title
    fun merge(vararg others: String): String = others.joinToString()
}

/** A class may declare the interface itself; the plugin leaves it alone. */
class Summary(override val title: String) : Report {
    override suspend fun load(): String = title
    override fun merge(vararg others: String): String = others.size.toString()
}

/** `com.example.shapes.Sized` is declared in another module; this class never mentions it. */
class Photo(val width: Int, val height: Int)

fun area(target: Sized): Int = target.area()
fun describe(target: Labeled): String = "${target.name}=${target.label()}"
fun rank(target: Ranked): Int = target.compareTo("m")
fun bump(target: Counter) { target.count++ }
fun kindOf(target: Shape): Shape.Kind = target.kind
fun openInt(box: Box<Int>): Int = box.value
fun mapWith(mapper: Mapper): String = mapper.map("a")
fun countOn(shelf: Shelf): Int = shelf.items.count()
fun headlineOf(target: Report): String = target.headline()
fun mergedBy(target: Report): String = target.merge("a", "b")
suspend fun loadFrom(target: Report): String = target.load()
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
    kindOf(Disc(Shape.Kind.ROUND)),
    openInt(IntBox(7)),
    mapWith(Identity()),
    countOn(Books(listOf("a", "b"))),
    headlineOf(Page("p")),
    mergedBy(Page("p")),
    headlineOf(Summary("s")),
)
