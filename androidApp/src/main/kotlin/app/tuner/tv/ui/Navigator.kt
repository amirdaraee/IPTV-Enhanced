package app.tuner.tv.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf

sealed interface Screen {
    data object Home : Screen
    data object Welcome : Screen
    data class EditSource(val sourceId: Long?) : Screen
    data object LivePlayer : Screen
    data object VodPlayer : Screen
    data class MovieDetail(val movieId: Long) : Screen
    data class SeriesDetail(val seriesId: Long) : Screen
}

/** Minimal back stack; screens keep their state in activity-scoped ViewModels. */
class Navigator(start: Screen) {
    val stack = mutableStateListOf(start)
    val current: Screen get() = stack.last()

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun reset(screen: Screen) {
        stack.clear()
        stack.add(screen)
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator") }
