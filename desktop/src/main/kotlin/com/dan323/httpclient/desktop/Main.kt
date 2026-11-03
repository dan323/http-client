package com.dan323.httpclient.desktop

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Frame
import java.nio.file.Path
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main(args: Array<String>) = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "HTTP Client",
        state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            AppRoot(remember { AppState().also { st -> args.firstOrNull()?.let { st.open(Path.of(it)) } } }, window)
        }
    }
}

/** Everything inside the window; split out so UI tests can render it without a real window. */
@Composable
fun AppRoot(state: AppState, parent: Frame? = null) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    // Own the focus by default so the shortcuts work even when no field is focused.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Surface(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent { e ->
                // Ctrl/Cmd+S saves, Ctrl/Cmd+Enter sends the selected request.
                val command = e.isCtrlPressed || e.isMetaPressed
                if (e.type != KeyEventType.KeyDown || !command) false
                else when (e.key) {
                    Key.S -> { state.save(); true }
                    Key.Enter -> { if (!state.running) scope.launch(Dispatchers.IO) { state.run(all = false) }; true }
                    else -> false
                }
            },
        ) { App(state, parent) }
    }
}
