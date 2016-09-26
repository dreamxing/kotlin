// !API_VERSION: 1.0

val v1: String
    @SinceKotlin("1.1")
    get() = ""

@SinceKotlin("1.1")
val v2 = ""

var v3: String
    @SinceKotlin("1.1")
    get() = ""
    set(value) {}

var v4: String
    get() = ""
    @SinceKotlin("1.1")
    set(value) {}

var v5: String
    @SinceKotlin("1.1")
    get() = ""
    @SinceKotlin("1.1")
    set(value) {}

@SinceKotlin("1.1")
var v6: String
    get() = ""
    set(value) {}

fun test() {
    <!TODO!><!> // TODO: there should be more errors
    v1
    <!UNRESOLVED_REFERENCE!>v2<!>
    v3
    v3 = ""
    v4
    v4 = ""
    v5
    v5 = ""
    <!UNRESOLVED_REFERENCE!>v6<!>
    <!UNRESOLVED_REFERENCE!>v6<!> = ""
}
