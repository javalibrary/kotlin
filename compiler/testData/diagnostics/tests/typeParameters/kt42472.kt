import kotlin.reflect.KProperty

fun <T> id(): T = null!!
interface A<E>
fun <T> id1(): A<T> = null!!

fun main() {

    id1() as A<String>

//    foo(id1())
}

fun foo(x: A<String>) {}
