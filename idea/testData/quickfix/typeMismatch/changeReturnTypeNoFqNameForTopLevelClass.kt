// "Change return type of current function 'A.foo' to 'Int'" "true"
package foo.bar

class A {
    fun foo(): String {
        return <caret>1
    }
}

