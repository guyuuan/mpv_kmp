package com.guyuuan.mpv_kmp.example

import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import org.jetbrains.skiko.FPSCounter

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
//    System.setProperty("compose.swing.render.on.graphics", "true")
//    System.setProperty("mpv.kmp.desktop.render", "hardware")
//    System.setProperty("compose.interop.blending", "true")

    application {
        Window(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(size = DpSize(400.dp, 300.dp)),
            title = "Mpv KMP Example"
        ) {
            val fps by FPS()
            App(overlay = {
                Text(
                    "FPS: $fps",
                    modifier = Modifier.align(alignment = Alignment.TopEnd),
                    fontSize = 18.sp,
                    color = Color.White
                )
            })
        }
    }
}

@Composable
fun FPS(): State<Int> {
    val fpsFlow = remember {
        MutableStateFlow<Int>(0)
    }
    LaunchedEffect(fpsFlow) {
        val fpsCounter = FPSCounter(logOnTick = true)
        while (currentCoroutineContext().isActive) {
            withFrameNanos {
                fpsFlow.update { fpsCounter.average }
                fpsCounter.tick()
            }
        }
    }

    return fpsFlow.collectAsStateWithLifecycle()
}