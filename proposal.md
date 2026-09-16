I want to make a proof of concept of the following kotlin library

I want to bring structural typing into kotlin that has lack of it

How could API look like:

I want to define method that accepts object with some parameters. For example:

interface Sized {
  val width: Int
  val height: Int
}

fun size(target: Sized) = target.width * target.height

class Rectangular(val width: Int, val height: Int, val color: String)

size(Rectangular(1, 2, "red"))

We could think how could it be solved. The main idea - is creating compiler plugin that adds overrides for particular usages. But we are open for other variants too.

For example user could define package for scan (to avoid full scan) and annotation on the target interface, let's say `@Structural` for now

so the code looks like:

@Structural
interface Sized {...}

and compiler plugin should generate something like:

fun size(target: Rectangular) {
class RectangularProxy(delegate: Rectangular): Sized {
...
}
  size (RectangularProxy(target))
}

as a first variant we could pregenerate all possible targets on compilation time.

we could scan defined package and check every class, and if that class contains required fields we could generate the method we need.

first simple optimization for that case: if base class fits structural interface we dont need to generate something for child classes.

