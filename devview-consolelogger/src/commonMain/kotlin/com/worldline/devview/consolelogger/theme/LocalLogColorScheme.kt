package com.worldline.devview.consolelogger.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.luminance
import co.touchlab.kermit.Logger as KermitLogger

/**
 * CompositionLocal for providing the [LogColorScheme] used by the Console module's UI.
 *
 * Provide this where your app already configures its `MaterialTheme`, so the console
 * palette switches alongside your light/dark theme:
 *
 * ```kotlin
 * MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
 *     CompositionLocalProvider(
 *         LocalLogColorScheme provides if (darkTheme) LogColorScheme.Dark else LogColorScheme.Light
 *     ) {
 *         // ... DevView content ...
 *     }
 * }
 * ```
 *
 * If never provided, the console falls back to [LogColorScheme.Light]/[LogColorScheme.Dark]
 * chosen from the ambient `MaterialTheme`'s surface luminance, and logs a one-time warning
 * pointing at this CompositionLocal (see the "Theming" guide).
 *
 * @see LogColorScheme
 */
public val LocalLogColorScheme: ProvidableCompositionLocal<LogColorScheme?> =
    staticCompositionLocalOf { null }

// ponytail: plain top-level flag, not thread-safe under concurrent first compositions.
// Guards a diagnostic log line, not app state — a rare duplicate warning is harmless.
private var hasWarnedMissingLogColorScheme = false

/**
 * Resolves the [LogColorScheme] to use: [LocalLogColorScheme] if provided, otherwise a
 * best-effort guess derived from the ambient `MaterialTheme`'s surface luminance.
 *
 * The luminance-based guess intentionally does not use `isSystemInDarkTheme()` — DevView
 * renders inside the host app's `MaterialTheme`, which may be driven by something other
 * than the system setting (e.g. a feature flag), so the theme actually in effect is the
 * only reliable signal.
 */
@Composable
@ReadOnlyComposable
internal fun rememberLogColorScheme(): LogColorScheme {
    val provided = LocalLogColorScheme.current
    if (provided != null) return provided

    if (!hasWarnedMissingLogColorScheme) {
        hasWarnedMissingLogColorScheme = true
        KermitLogger.w(tag = "DevViewConsole") {
            "LocalLogColorScheme was never provided; falling back to a palette guessed " +
                "from the current theme's surface luminance. Provide LocalLogColorScheme " +
                "at your app's MaterialTheme site to fix this — see the Theming guide."
        }
    }

    return if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        LogColorScheme.Dark
    } else {
        LogColorScheme.Light
    }
}
