# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Module Does

`devview-consolelogger` is the console-log-viewer module for the DevView developer menu. It
tails the platform's native console output — `logcat` on Android, a `stdout`/`stderr`
redirect on iOS — and shows it in a filterable, auto-following Compose UI, similar to
viewing Logcat in Android Studio.

The module is built around one hard requirement: capture must work on an **untethered**
device (no debugger attached), because testers install a release build and never plug into
a computer. See the capture matrix in `docs/modules/consolelogger.md` for exactly what each
platform can and cannot see without a debugger — the one real gap is `NSLog`/`os_log`/Swift
`Logger` on iOS, mitigated by `DevViewLogWriter`.

## Public API Surface

### `Console` — the `Module` entry point

```kotlin
Console(
    maxEntries: Int = ConsoleLogger.DEFAULT_MAX_ENTRIES,
    captureNativeConsole: Boolean = true
)
```

Registered in the host app's `buildModules { }` block. `captureNativeConsole = false`
disables the native tail entirely — only entries logged manually via `ConsoleLogger.log()`
or `DevViewLogWriter` will appear.

### `ConsoleLogger` — singleton sink

| Member | Visibility | Purpose |
|---|---|---|
| `log(log: ConsoleLog)` | `public` | Append an entry; safe from any thread |
| `logs: SnapshotStateList<ConsoleLog>` | `public` | Observable, bounded (ring) list |
| `hasLogs: Boolean` | `public` | Convenience check |
| `configure(maxEntries: Int)` | `internal` | Called once by `Console.initModule()` |
| `collectInto()` | `internal` | The channel-drain collector; called once by `Console.initModule()` |
| `clear()` | `internal` | Called by the in-UI "Clear" action only |

### `ConsoleLog` — entry record

```kotlin
data class ConsoleLog(
    val level: LogLevel,
    val tag: String,       // empty when the source has no concept of a tag (iOS stdout)
    val message: String,
    val timestamp: Long
)
```

### `DevViewLogWriter` — optional Kermit bridge

`public object DevViewLogWriter : LogWriter()`. Forwards Kermit `Logger` calls into
`ConsoleLogger`. Register once: `Logger.addLogWriter(DevViewLogWriter)`.

### `LocalConsoleLogs` / `LocalLogColorScheme` — CompositionLocals

`LocalConsoleLogs: ProvidableCompositionLocal<List<ConsoleLog>>` works exactly like
`devview-analytics`'s `LocalAnalytics`. `LocalLogColorScheme:
ProvidableCompositionLocal<LogColorScheme?>` is nullable — see Theming below.

### `ConsoleScreen` — main composable

```kotlin
@Composable
fun ConsoleScreen(modifier: Modifier = Modifier, bottomPadding: Dp = 0.dp)
```

Reads from `LocalConsoleLogs.current`. All filtering state (level chips, text query) and
auto-follow state live as local `remember` state inside this composable.

## Internal Architecture

```
Native capture (androidMain / iosMain)          Manual calls
        │                                             │
        ▼                                             ▼
                    ConsoleLogger.log()
                    (Channel, capacity = maxEntries, DROP_OLDEST)
                              │
                              ▼
                    ConsoleLogger.collectInto()
                    (single collector, started by Console.initModule())
                              │
                              ▼
                    consoleLogs: SnapshotStateList<ConsoleLog>
                    (bounded ring, evicts from head past maxEntries)
                              │  provides via LocalConsoleLogs
                              ▼
                    ConsoleScreen (stateful Compose UI)
                        ├── bottom bar: LogLevel FilterChips + text filter field
                        └── LazyColumn, auto-follow unless manually scrolled
```

`log()` writes into a channel rather than appending directly to the snapshot list — unlike
`AnalyticsLogger`, which appends hand-placed events synchronously. Native capture can emit
far faster than the UI can usefully render, so the channel (a) makes `log()` safe to call
from the background capture threads and (b) lets `collectInto()`'s tight drain loop absorb
a burst in one uninterrupted pass; Compose coalesces the resulting snapshot writes into a
single recomposition rather than one per line.

`Console.initModule()` calls `ConsoleLogger.configure(maxEntries)` then launches two
coroutines: the `collectInto()` drain, and — if `captureNativeConsole` — the platform
capture. The capture coroutine is wrapped in its own `try`/`catch` (rethrowing
`CancellationException`) so a capture failure (e.g. an OEM ROM blocking `logcat`) can't
cancel the sibling drain job and silently break manual logging too.

## Platform-Specific Code

This is the first DevView feature module with real `androidMain`/`iosMain` source sets and
`expect`/`actual` declarations (`devview-analytics` and `devview-timecapsule` have none).

**`ConsoleCapture.kt`** (`commonMain`) declares `internal expect suspend fun
tailNativeConsole()`. Runs until the calling coroutine is cancelled.

**`ConsoleCapture.android.kt`**: spawns `logcat -v brief --pid=<self>` via `ProcessBuilder`,
parses each line with `parseLogcatLine` (`LogcatLine.kt`, `commonMain` — pure string logic,
tested directly in `commonTest` rather than needing an Android-only test source set). If
`ProcessBuilder.start()` throws `IOException` (some OEM ROMs block `logcat` for third-party
apps), it logs a warning via Kermit and returns — native capture is disabled for that
session, but `ConsoleLogger.log()`/`DevViewLogWriter` keep working.

**`ConsoleCapture.ios.kt`**: redirects `stdout` and `stderr` through separate in-process
`platform.posix` pipes (`dup`/`pipe`/`dup2`), so it works without a debugger attached —
this is what makes iOS capture untethered-safe. Key details, in case this needs revisiting:
- The write end (the fd `dup2`'d over `STDOUT_FILENO`/`STDERR_FILENO`) is set `O_NONBLOCK`.
  Without it, a stalled reader fills the pipe buffer and every subsequent `println` in the
  host app blocks forever; with it, writes past capacity drop instead — the correct
  trade-off for a dev tool.
- `stdout` is switched to `_IOLBF` (line-buffered) via `setvbuf`, since a pipe isn't a tty
  and libc would otherwise default to 4KB full buffering, arriving in bursts.
- Bytes read from the pipe are written back to the *original* fd (`saved`, from `dup()`
  before redirecting) so Xcode's console still shows everything when the device is
  tethered — the redirect is additive, not a replacement.
- Cannot see `NSLog`/`os_log`/Swift `Logger` — those bypass the file descriptor. This is
  the gap `DevViewLogWriter` exists to mitigate.

No cinterop `.def` files or Swift shims are needed for any of this — everything used is
already exposed by Kotlin/Native's bundled `platform.posix` and `kotlinx.cinterop`.

## Theming

`LogColorScheme` (`theme/LogColorScheme.kt`) holds two complete, hand-tuned palettes
(`Light`/`Dark`) covering all seven `LogLevel` entries — ported verbatim from a prior
Android-only implementation, deliberately *not* derived from `MaterialTheme.colorScheme`,
since log-level colors need to stay visually distinct and theme-independent.

Integrators provide the palette via `LocalLogColorScheme` at their app's theme site (see
`docs/guides/theming.md`), not via a `Console` constructor parameter — `rememberModules { }`
(where `Console` is constructed) typically runs before the host's dark/light state is
readable, and a constructor param would be captured once rather than staying reactive to a
runtime theme toggle. `theme/LocalLogColorScheme.kt`'s internal `rememberLogColorScheme()`
resolves the CompositionLocal, or falls back to a luminance-based guess with a one-time
Kermit warning if it was never provided.

## Non-Obvious Patterns

- **`ConsoleLogger.DEFAULT_MAX_ENTRIES`** is also the channel capacity used before
  `configure()` runs — so `log()`/`DevViewLogWriter` calls made very early (before
  `Console.initModule()` composes) aren't lost, just buffered against the default.
- **Bounded ring, not unbounded list.** The prior Android-only implementation used an
  unbounded `mutableStateListOf`, which could OOM on a sustained logcat firehose. Both the
  channel (capacity `maxEntries`, `DROP_OLDEST`) and the retained `consoleLogs` list
  (evicts from the head) are bounded to the same figure.
- **`DevViewLogWriter` + Android `LogcatWriter` double-logs.** If a host app registers both
  `DevViewLogWriter` and Kermit's own `LogcatWriter` on Android, every Kermit call appears
  twice in the Console screen — once via the writer, once via the logcat tail picking up
  what `LogcatWriter` already wrote to logcat. Documented as a known trade-off, not a bug.

## Testing

`commonTest` covers `ConsoleLogger` (ring eviction, `clear()`), `LogcatLine` (every priority
prefix, the `UNKNOWN` fallback for unparseable lines), `DevViewLogWriter` (`Severity`
mapping, throwable formatting), `Console` (module metadata/actions), and `LogColorScheme`
(`get(level)`, `copy()`). `androidDeviceTest` covers `ConsoleScreen` (empty state, text
filter, level-chip filter) and `rememberLogColorScheme()`'s resolution logic.

The `ConsoleCapture.ios.kt` actual has no automated test — it can only be exercised on a
Mac. CI verifies it compiles via the `ios-build` job (`compileKotlinIosArm64
iosSimulatorArm64Test`) on `macos-26`; manual verification requires running the sample on
an iOS simulator or device and confirming `println` output reaches the Console screen while
still appearing in the Xcode console (the tee-back).
