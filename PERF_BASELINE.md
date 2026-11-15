# Performance Baseline (`PERF_BASELINE.md`)

Measured on **Google TV (`Chromecast HD` / `boreal`, Android 14 / API 34)** against the baseline `org.jellyfin.androidtv.debug` APK (commit `959ebce32`) before any SyncPlay refactor or UI redesign.

## Target Hardware Specs

- **Device**: `Chromecast HD` (`boreal`)
- **OS**: Android 14 (API 34)
- **RAM**: `1,439,364 kB` (~1.4 GB total); `278,872 kB` free / `470,976 kB` available post-cleanup
- **Memory Constraints**: Low-RAM TV profile (`com.android.tv.overlay.lowram`), zram swap active (`~690 MB` configured)

## Cold Start (`am start -W org.jellyfin.androidtv.debug/.ui.startup.StartupActivity`)

| Measurement | Result | Notes |
|---|---|---|
| Pre-cleanup runs 1–3 (`StartupActivity` launch) | `3050 ms`, `3023 ms`, `3016 ms` (avg `3030 ms`) | Before bloatware disabling |
| Post-cleanup run (`StartupActivity` launch) | `TotalTime: 4903 ms`, `WaitTime: 4916 ms` | Debuggable + JIT runtime, before `MainActivity` inflation |
| Full settle note | First interactive Home load observed triggering ANR if key events sent before ~8–12s on cold debug build | `Choreographer: Skipped 69 frames`, concurrent copying/compact GC pause `~799 ms` |

## Home Navigation Frame Metrics (`dumpsys gfxinfo org.jellyfin.androidtv.debug`)

### Run A — 12 D-pad navigations (Down × 4, Right × 4, Up × 4) across Home rows
- **Total frames rendered**: `31`
- **Janky frames**: `29 (93.55%)`
- **Percentiles**: 50th = `600 ms`, 90th = `3400 ms`, 95th = `4000 ms`, 99th = `4400 ms`
- **Breakdown**: Missed Vsync = `26`, High input latency = `4`, Slow UI thread = `29`, Slow issue draw commands = `29`, Frame deadline missed = `29`
- **GPU percentiles**: 50th = `20 ms`

### Run B — 3 D-pad navigations (Down × 1, Right × 2) (`framestats`)
- **Total frames rendered**: `10`
- **Janky frames**: `6 (60.00%)`
- **Percentiles**: 50th = `129 ms`, 90th = `350 ms`, 95th = `350 ms`, 99th = `350 ms`
- **Breakdown**: Missed Vsync = `5`, High input latency = `8`, Slow UI thread = `6`, Slow issue draw commands = `5`
- **GPU 50th percentile**: `17 ms`

## Home Memory Usage (`dumpsys meminfo org.jellyfin.androidtv.debug`, pid `7912`)

| Metric | Value |
|---|---|
| **TOTAL PSS** | `192,876 kB` (~188.4 MB) |
| **TOTAL RSS** | `264,000 kB` |
| **TOTAL SWAP PSS** | `322 kB` |
| **Java Heap (Pss)** | `24,848 kB` (`Dalvik Heap: 18,168 kB`, `Alloc: 12,143 kB`) |
| **Native Heap (Pss)** | `27,116 kB` |
| **Graphics / GL mtrack (Pss)** | `57,948 kB` (Skia GPU caches: `41.62 MB`, Scratch RenderTarget `39.36 MB`) |
| **Code (Pss)** | `54,668 kB` |
| **Objects** | `Views: 348`, `ViewRootImpl: 1` |

## Player Engine & UI Inventory Findings

- **Playback Engine**: Media3 `ExoPlayer` (`androidx.media3:1.11.1`) wrapped by `VideoManager` (+ optional `AssHandler`/`AssRenderersFactory` when `assDirectPlay` is enabled). No LibVLC video playback path.
- **SyncPlay Critical Audit Findings**:
  1. `VideoManager.onIsPlayingChanged(true)` invokes `mPlaybackControllerNotifiable.onPrepared()` on every resume/unpause/rebuffer, not just initial stream prepare.
  2. `PlaybackControllerHelperKt.setSubtitleIndex()` (for burn-in `ENCODE` or disabling burn-in) and `PlaybackController.switchAudioStream()` (when transcoding) call `stop()` followed by `play(...)`/`playInternal(...)`, causing spurious `IDLE`/stop + `onUserStartPlayback` (`setNewQueue`) + forced auto-play even when paused, plus resetting `mStartPosition`/`mCurrentPosition` tracking.
  3. `PlaybackController.seek()` unconditionally calls `mVideoManager.play()` and sets `PlaybackState.PLAYING`, causing remote `Seek` commands while SyncPlay group state is `Paused` or `Waiting` to unpause the TV out of sync with other clients.
  4. `SyncPlayPlaybackIntegration` relies on synchronous boolean `isHandlingRemoteCommand` around asynchronous `controller.play()`/`pause()`/`seek()` calls, leaving ExoPlayer callbacks firing after the flag resets, and `observeGroupState()` races against `executeRemoteCommand()`'s scheduled `When` delay.
- **UI Architecture Inventory**:
  - **Compose**: `MainActivity` root, `ui/base/*`, `ui/settings/*`, `ui/shared/toolbar/*`, `ui/search/composable/*`, `ui/browsing/composable/inforow/*`, `ui/composable/*`.
  - **Leanback / Legacy Views / XML**: `HomeFragment` (`AndroidFragment<HomeRowsFragment>`), `ui/browsing/*` (`BrowseGridFragment`, `BrowseViewFragment`, `EnhancedBrowseFragment`), `ui/itemdetail/*` (`FullDetailsFragment`, `ItemListFragment`), `ui/playback/*` (`CustomPlaybackOverlayFragment`, `overlay/*`), `ui/startup/fragment/*`.

## Post-Redesign Targets (Must Meet or Beat)

- **Janky frames**: `< 5%` during continuous Home & Grid D-pad scrolling (measured on release/speed-compiled build)
- **Cold start**: `<= baseline`
- **RAM (TOTAL PSS)**: `<= baseline + 15%` (`<= 221,807 kB` on Home)
