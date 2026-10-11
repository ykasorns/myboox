package com.ykasorns.ebookreader.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.content.edit
import com.ykasorns.ebookreader.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Page colours, modelled on the four themes of iPad Books. Colours are ARGB. */
enum class ReaderTheme(
    val background: Long,
    val text: Long,
    val secondary: Long,
    val link: Long,
    val isDark: Boolean,
    @StringRes val label: Int,
) {
    WHITE(0xFFFFFFFF, 0xFF1D1D1F, 0xFF8E8E93, 0xFF2F6FDB, false, R.string.theme_white),
    PAPER(0xFFF6EEDF, 0xFF4A3524, 0xFF9C8670, 0xFF9A5B1E, false, R.string.theme_paper),
    GRAY(0xFF46464A, 0xFFE3E3E6, 0xFFA1A1A6, 0xFF8AB4F8, true, R.string.theme_gray),
    NIGHT(0xFF000000, 0xFFC9C9CC, 0xFF6E6E73, 0xFF7FA8E8, true, R.string.theme_night),
}

enum class ReaderFont(val css: String, @StringRes val label: Int) {
    ORIGINAL("", R.string.font_original),
    SANS("sans-serif", R.string.font_sans),
    SERIF("serif", R.string.font_serif),
}

enum class LineSpacing(val value: Float, @StringRes val label: Int) {
    TIGHT(1.3f, R.string.spacing_tight),
    NORMAL(1.55f, R.string.spacing_normal),
    LOOSE(1.85f, R.string.spacing_loose),
}

enum class PageMargin(val dp: Float, @StringRes val label: Int) {
    NARROW(14f, R.string.margin_narrow),
    NORMAL(26f, R.string.margin_normal),
    WIDE(44f, R.string.margin_wide),
}

enum class LibrarySort { RECENT, TITLE, AUTHOR }

data class ReaderSettings(
    /** Text size as a percentage of the book's own size. */
    val fontScale: Int = 100,
    val theme: ReaderTheme = ReaderTheme.WHITE,
    val font: ReaderFont = ReaderFont.ORIGINAL,
    val lineSpacing: LineSpacing = LineSpacing.NORMAL,
    val margin: PageMargin = PageMargin.NORMAL,
    val justify: Boolean = false,
    val twoPageLandscape: Boolean = true,
    val volumeKeys: Boolean = true,
    val keepScreenOn: Boolean = true,
    /** 0..1, or a negative value to follow the system brightness. */
    val brightness: Float = -1f,
) {
    companion object {
        const val MIN_FONT_SCALE = 60
        const val MAX_FONT_SCALE = 250
        const val FONT_SCALE_STEP = 10
    }
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())

    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    fun update(transform: (ReaderSettings) -> ReaderSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        write(next)
    }

    var librarySort: LibrarySort
        get() = enumOr(prefs.getString("librarySort", null), LibrarySort.RECENT)
        set(value) = prefs.edit { putString("librarySort", value.name) }

    private fun read() = ReaderSettings(
        fontScale = prefs.getInt("fontScale", 100)
            .coerceIn(ReaderSettings.MIN_FONT_SCALE, ReaderSettings.MAX_FONT_SCALE),
        theme = enumOr(prefs.getString("theme", null), ReaderTheme.WHITE),
        font = enumOr(prefs.getString("font", null), ReaderFont.ORIGINAL),
        lineSpacing = enumOr(prefs.getString("lineSpacing", null), LineSpacing.NORMAL),
        margin = enumOr(prefs.getString("margin", null), PageMargin.NORMAL),
        justify = prefs.getBoolean("justify", false),
        twoPageLandscape = prefs.getBoolean("twoPageLandscape", true),
        volumeKeys = prefs.getBoolean("volumeKeys", true),
        keepScreenOn = prefs.getBoolean("keepScreenOn", true),
        brightness = prefs.getFloat("brightness", -1f),
    )

    private fun write(s: ReaderSettings) = prefs.edit {
        putInt("fontScale", s.fontScale)
        putString("theme", s.theme.name)
        putString("font", s.font.name)
        putString("lineSpacing", s.lineSpacing.name)
        putString("margin", s.margin.name)
        putBoolean("justify", s.justify)
        putBoolean("twoPageLandscape", s.twoPageLandscape)
        putBoolean("volumeKeys", s.volumeKeys)
        putBoolean("keepScreenOn", s.keepScreenOn)
        putFloat("brightness", s.brightness)
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
