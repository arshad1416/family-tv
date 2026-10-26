package app.familytv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

/**
 * What a page last loaded in this run, so it draws as it was when it comes back (Movies to Home)
 * while it loads again behind, never empty first. A value is used again only for the same inputs
 * (the categories a viewer sees, the viewer), so another viewer never sees the last one's rows.
 */
internal object Kept {
    private val values = ConcurrentHashMap<String, Pair<Any, Any>>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String, inputs: Any = Unit): T? = values[key]?.takeIf { it.first == inputs }?.second as T?

    fun put(key: String, inputs: Any = Unit, value: Any) {
        values[key] = inputs to value
    }
}

/**
 * [collectAsState], starting from the value last collected under [key] in this run instead of
 * [empty], when it was collected for the same [inputs] (such as the picked viewer).
 */
@Composable
internal fun <T : Any> Flow<T>.collectKept(key: String, empty: T, inputs: Any = Unit): State<T> {
    val state = collectAsState(initial = remember(key) { Kept.get(key, inputs) ?: empty })
    SideEffect { Kept.put(key, inputs, state.value) }
    return state
}
