package com.guyuuan.mpv_kmp.config

import com.guyuuan.mpv_kmp.props.MpvSubtitleProperties

data class MpvConfig(
    val fontConfig: FontConfig = FontConfig(),
    val other: Map<String, String>? = null
) {
    fun toMap(): Map<String, String> = buildMap {
        other?.let(::putAll)
        put(MpvSubtitleProperties.FONT_DIR, fontConfig.subFontsDir)
        put(MpvSubtitleProperties.FONT, fontConfig.subFont)
        put(MpvSubtitleProperties.FONT_SIZE, fontConfig.subFontSize.toString())
        put(MpvSubtitleProperties.MARGIN_Y, fontConfig.subMarginY.toString())
    }
}
