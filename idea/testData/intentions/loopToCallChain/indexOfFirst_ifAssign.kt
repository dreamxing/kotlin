// WITH_RUNTIME
// INTENTION_TEXT: "Replace with 'indexOfFirst{}'"
fun foo(list: List<String>) {
    var result = -1
    <caret>for ((index, s) in list.withIndex()) {
        if (s.length > 0) {
            result = index
            break
        }
    }
}