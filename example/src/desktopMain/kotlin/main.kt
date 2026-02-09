package com.guyuuan.mpv_kmp.example

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Mpv KMP Example") {
        App()
    }
}
