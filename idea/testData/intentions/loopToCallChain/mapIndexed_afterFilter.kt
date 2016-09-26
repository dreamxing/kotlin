// WITH_RUNTIME
// INTENTION_TEXT: "Replace with 'filterNot{}.mapIndexed{}.firstOrNull{}'"
fun foo(list: List<String>): Int? {
    var index = 0
    <caret>for (s in list) {
        if (s.isBlank()) continue
        val x = s.length * index
        if (x > 0) return x
        index++
    }
    return null
}