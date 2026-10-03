package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.model.ReaderAlignment
import io.github.nimbice.fanos.core.model.ReaderFont
import io.github.nimbice.fanos.core.model.ReaderSettings
import io.github.nimbice.fanos.core.model.ReaderTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.nimbice.fanos.core.model.CustomReaderColors
import io.github.nimbice.fanos.core.model.PageTurn
import io.github.nimbice.fanos.core.model.TapZones
import io.github.nimbice.fanos.core.model.ScrollBar

/**
 * A novel's own reading settings as the database and backups keep them: choices by name, so they read back
 * whatever order the app lists them in. A value missing or unknown (a font a later app dropped) comes back as
 * the default. The phone-wide settings (screen on, volume keys, brightness and warmth) aren't a novel's, so they aren't kept.
 */
@Serializable
internal data class StoredReaderSettings(
    val theme: String? = null,
    val customBackground: Int? = null,
    val customText: Int? = null,
    val customLink: Int? = null,
    val font: String? = null,
    val ownFont: String? = null,
    val fontWeight: Int? = null,
    val fontSize: Int? = null,
    val lineHeight: Float? = null,
    val paragraphSpacing: Float? = null,
    val margin: Int? = null,
    val alignment: String? = null,
    val pageMode: Boolean? = null,
    val twoPages: Boolean? = null,
    val pageTurn: String? = null,
    val scrollBar: String? = null,
    val tapZones: String? = null,
) {
    fun toSettings(): ReaderSettings {
        val defaults = ReaderSettings()
        return defaults.copy(
            theme = ReaderTheme.entries.firstOrNull { it.name == theme } ?: defaults.theme,
            customColors =
                CustomReaderColors(
                    background = customBackground?.and(RGB) ?: defaults.customColors.background,
                    text = customText?.and(RGB) ?: defaults.customColors.text,
                    link = customLink?.and(RGB) ?: defaults.customColors.link,
                ),
            font = ReaderFont.entries.firstOrNull { it.name == font } ?: defaults.font,
            ownFont = ownFont,
            fontWeight = fontWeight?.coerceIn(MIN_WEIGHT, MAX_WEIGHT) ?: defaults.fontWeight,
            fontSize = fontSize ?: defaults.fontSize,
            lineHeight = lineHeight ?: defaults.lineHeight,
            paragraphSpacing = paragraphSpacing ?: defaults.paragraphSpacing,
            margin = margin ?: defaults.margin,
            alignment = ReaderAlignment.entries.firstOrNull { it.name == alignment } ?: defaults.alignment,
            pageMode = pageMode ?: defaults.pageMode,
            twoPages = twoPages ?: defaults.twoPages,
            pageTurn = PageTurn.entries.firstOrNull { it.name == pageTurn } ?: defaults.pageTurn,
            scrollBar = ScrollBar.entries.firstOrNull { it.name == scrollBar } ?: defaults.scrollBar,
            tapZones = TapZones.entries.firstOrNull { it.name == tapZones } ?: defaults.tapZones,
        )
    }

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private const val RGB = 0xFFFFFF
        private const val MIN_WEIGHT = 300
        private const val MAX_WEIGHT = 700

        fun of(settings: ReaderSettings) =
            StoredReaderSettings(
                theme = settings.theme.name,
                customBackground = settings.customColors.background,
                customText = settings.customColors.text,
                customLink = settings.customColors.link,
                font = settings.font.name,
                ownFont = settings.ownFont,
                fontWeight = settings.fontWeight,
                fontSize = settings.fontSize,
                lineHeight = settings.lineHeight,
                paragraphSpacing = settings.paragraphSpacing,
                margin = settings.margin,
                alignment = settings.alignment.name,
                pageMode = settings.pageMode,
                twoPages = settings.twoPages,
                pageTurn = settings.pageTurn.name,
                scrollBar = settings.scrollBar.name,
                tapZones = settings.tapZones.name,
            )

        /** The settings in [json]; null when it can't be read, and the novel follows the defaults. */
        fun parse(json: String): StoredReaderSettings? = runCatching { JSON.decodeFromString(serializer(), json) }.getOrNull()
    }
}
