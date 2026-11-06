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
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.runtime.snapshotFlow

fun main(args: Array<String>) = application {
    val state = remember { createAppState(args) }
    val placement = remember { WindowGeometry.restore(state.savedWindow(), WindowGeometry.currentScreens()) }
    val windowState = rememberWindowState(
        size = DpSize(placement.width.dp, placement.height.dp),
        position = if (placement.x != null && placement.y != null) WindowPosition(placement.x.dp, placement.y.dp) else WindowPosition.PlatformDefault,
        placement = if (placement.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
    )
    LaunchedEffect(windowState) {
        // Remember the normal (not maximized) geometry plus the maximized flag; the saver debounces bursts.
        val tracker = WindowTracker(placement)
        snapshotFlow { Triple(windowState.size, windowState.position, windowState.placement) }.collect { (size, pos, place) ->
            val abs = pos as? WindowPosition.Absolute
            state.rememberWindow(tracker.observe(
                size.width.value.toInt(), size.height.value.toInt(),
                abs?.x?.value?.toInt(), abs?.y?.value?.toInt(), place == WindowPlacement.Maximized,
            ))
        }
    }
    Window(
        onCloseRequest = {
            // Closing flushes the settings and waits for in-flight exchanges; do not let a stall keep the window open.
            runWithin(2000) { state.close() }
            exitApplication()
        },
        title = "HTTP Client",
        state = windowState,
    ) {
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            AppRoot(state, window)
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
