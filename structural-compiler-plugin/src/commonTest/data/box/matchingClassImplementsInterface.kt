// A class whose members match a @Structural interface is compiled as if it declared it.
import com.obabichev.structural.Structural

@Structural
interface Sized {
    val width: Int
    val height: Int
    fun area(): Int
}

class Rectangular(val width: Int, val height: Int, val color: String) {
    fun area(): Int = width * height
}

fun size(target: Sized): Int = target.area()

fun box(): String {
    val rectangular = Rectangular(2, 3, "red")
    if (size(rectangular) != 6) return "Fail: size"
    if ((rectangular as Any) !is Sized) return "Fail: is check"
    val sized: Sized = rectangular
    if (sized !== rectangular) return "Fail: identity"
    return "OK"
}
