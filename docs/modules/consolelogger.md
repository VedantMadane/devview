# Console Logger Module

Displays the app's native console output inside the DevView overlay — logcat on Android, a
`stdout`/`stderr` redirect on iOS — like viewing Logcat in Android Studio, with per-level
filter chips and a text filter.

## Overview

Native capture works on an **untethered** device — no debugger required — which is the
main requirement this module is built around: a tester installs a release build and opens
DevView without ever plugging into a computer.

| Log source | Android | iOS |
|---|---|---|
| `android.util.Log` (incl. third-party libs) | ✅ logcat tail | n/a |
| Kotlin `println` | ✅ (ART redirects stdout → logcat) | ✅ stdout pipe |
| Swift `print`, C `printf` | n/a | ✅ stdout pipe |
| `NSLog` / `os_log` / Swift `Logger` | n/a | ❌ **not capturable** |
| Kermit, via `DevViewLogWriter` | ✅ | ✅ (in-process, no debugger needed) |

The one real gap is `NSLog`/`os_log` on iOS: since iOS 10, `NSLog` only reaches `stderr`
when a debugger is attached, and `os_log`/Swift `Logger` bypass the file descriptor
entirely. Closing that gap fully would require reading the unified log via `OSLogStore`,
which needs an `@objc` Swift shim and cinterop wiring — out of scope for now. The practical
mitigation is `DevViewLogWriter` (see below): route your app's logging through Kermit and
it reaches the console reliably on both platforms, tethered or not.

Retained history is bounded (default 1000 entries, oldest evicted first) so a logcat-speed
firehose can't grow memory unbounded.

## Installation

```kotlin
dependencies {
    implementation("com.worldline.devview:devview-consolelogger:<version>")
}
```

## Core API

### `Console`

The DevView module:

```kotlin
public class Console(
    public val maxEntries: Int = ConsoleLogger.DEFAULT_MAX_ENTRIES,
    public val captureNativeConsole: Boolean = true
)
```

```kotlin
val modules = rememberModules {
    module(Console())
}
```

Set `captureNativeConsole = false` to disable the native tail and only show entries logged
manually via `ConsoleLogger.log()` or `DevViewLogWriter`.

### `ConsoleLogger`

The log sink, in the same shape as `AnalyticsLogger`:

```kotlin
public object ConsoleLogger {
    public fun log(log: ConsoleLog)
    public val logs: SnapshotStateList<ConsoleLog>
    public val hasLogs: Boolean
}
```

`log()` is safe to call from any thread, including the background thread(s) driving native
capture.

### `DevViewLogWriter`

An optional [Kermit](https://github.com/touchlab/kermit) `LogWriter` that forwards
structured log calls into the same sink:

```kotlin
Logger.addLogWriter(DevViewLogWriter)
```

This is the reliable way to see logs on an iOS device with no debugger attached. On
Android, using this **alongside** Kermit's own `LogcatWriter` will show each entry twice —
once via this writer, once via the native logcat tail picking up what `LogcatWriter` wrote.
Use one or the other for a given log call, or accept the duplication.

## Theming

Log-level colors (a debug teal, a warning yellow, an error red, etc.) are a fixed,
hand-tuned palette per theme, not derived from `MaterialTheme.colorScheme` — see the
[Theming guide](../guides/theming.md#console-log-level-colors) for how to provide and
override them.

## Usage

```kotlin
val modules = rememberModules {
    module(Console())
}

// Optional: route Kermit-based logging into the same view.
Logger.addLogWriter(DevViewLogWriter)
```

Opening DevView → Console shows captured lines tailing live, colored by level. Selecting
one or more level chips filters to just those levels; selecting none shows everything.
Typing in the filter field matches against both tag and message. Scrolling up disables
auto-follow; a floating action button jumps back to the latest entry and resumes it.

## Sample

See `sample/shared/.../DevViewApp.kt` for a runnable end-to-end example, including
`LocalLogColorScheme` wired to the sample's dark-mode toggle.

## API Reference
> _[Dokka API Reference](../api/devview-consolelogger/index.html)_
