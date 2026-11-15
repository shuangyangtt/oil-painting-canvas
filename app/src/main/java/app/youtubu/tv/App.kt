package app.youtubu.tv

import androidx.compose.runtime.Composable

@Composable
fun App(state: AppState) {
    when (val screen = state.screen) {
        Screen.Home -> HomeScreen(state)
        is Screen.Player -> PlayerScreen(state, screen)
        Screen.Parent -> ParentScreen(state)
        Screen.Offline -> OfflineScreen(state)
    }
}
