// WITH_RUNTIME
import java.util.ArrayList

fun foo(list: List<String>): List<Int> {
    val result = ArrayList<Int>()
    <caret>for (s in list) {
        if (s.length > 0)
            result.add(s.hashCode())
    }
    return result
}