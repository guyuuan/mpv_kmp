package com.guyuuan.kmp.mpv.config

import com.guyuuan.kmp.mpv.props.MpvSubtitleProperties

class MpvConfig(
    val fontConfig: FontConfig = FontConfig(),
    other: Map<String, String>? = null,
    val logLevel: MpvLogLevel? = null
) {
    val other: Map<String, String>? = other?.toMap()

    class Builder {
        var fontConfig: FontConfig = FontConfig()
        var other: Map<String, String>? = null
        var logLevel: MpvLogLevel? = null

        fun build(): MpvConfig = MpvConfig(
            fontConfig = fontConfig,
            other = other,
            logLevel = logLevel
        )
    }

    operator fun component1(): FontConfig = fontConfig

    operator fun component2(): Map<String, String>? = other

    operator fun component3(): MpvLogLevel? = logLevel

    fun copy(
        fontConfig: FontConfig = this.fontConfig,
        other: Map<String, String>? = this.other,
        logLevel: MpvLogLevel? = this.logLevel
    ): MpvConfig = MpvConfig(fontConfig, other, logLevel)

    override fun equals(other: Any?): Boolean =
        this === other || other is MpvConfig &&
            fontConfig == other.fontConfig &&
            this.other == other.other &&
            logLevel == other.logLevel

    override fun hashCode(): Int {
        var result = fontConfig.hashCode()
        result = 31 * result + (other?.hashCode() ?: 0)
        result = 31 * result + (logLevel?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "MpvConfig(fontConfig=$fontConfig, other=$other, logLevel=$logLevel)"

    fun toMap(): Map<String, String> = buildMap {
        other?.let(::putAll)
        put(MpvSubtitleProperties.FONT_DIR, fontConfig.subFontsDir)
        put(MpvSubtitleProperties.FONT, fontConfig.subFont)
        put(MpvSubtitleProperties.FONT_SIZE, fontConfig.subFontSize.toString())
        put(MpvSubtitleProperties.MARGIN_Y, fontConfig.subMarginY.toString())
    }
}
