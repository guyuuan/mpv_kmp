package com.guyuuan.kmp.mpv.config

data class FontConfig(
    val subFontsDir: String = DEFAULT_SUB_FONTS_DIR,
    val subFont: String = DEFAULT_SUB_FONT,
    val subFontSize: Float = DEFAULT_SUB_FONT_SIZE,
    val subMarginY: Int = DEFAULT_SUB_MARGIN_Y
) {
    companion object {
        const val DEFAULT_SUB_FONTS_DIR = "~~/fonts"
        const val DEFAULT_SUB_FONT = "sans-serif"
        const val DEFAULT_SUB_FONT_SIZE = 38f
        const val DEFAULT_SUB_MARGIN_Y = 80
    }
}
