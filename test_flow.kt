import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val f1 = flowOf(1)
    val f2 = flowOf(2)
    val f3 = flowOf(3)
    val f4 = flowOf(4)
    val f5 = flowOf(5)
    val f6 = flowOf(6)
    val f7 = flowOf(7)
    
    combine(f1, f2, f3, f4, f5, f6, f7) { _ ->
        
    }.collect()
}
