# Performance Comparison Report (`PERF_REPORT.md`)

Measured on **Google TV (`Chromecast HD` / `boreal`, Android 14 / API 34, `1,439,364 kB` total RAM)** comparing the baseline `org.jellyfin.androidtv.debug` APK (`PERF_BASELINE.md`, commit `959ebce32`) against the signed, R8-minified, AOT-compiled (`cmd package compile -m speed -f org.jellyfin.androidtv`) Release APK (`outputs/jellyfin-androidtv-syncplay-release.apk`, `24.4 MB` / `24,465,570` bytes).

---

## 1. Cold Start (`am start -W .../StartupActivity`)

| Build | Runs (`TotalTime`) | Average `TotalTime` | Delta vs Baseline |
|---|---|---|---|
| **Baseline (`.debug`, JIT)** | Pre-cleanup: `3050 ms`, `3023 ms`, `3016 ms`<br>Post-cleanup: `4903 ms` (`WaitTime: 4916 ms`) | `3030 ms` / `4903 ms` | — |
| **Release R8 + AOT (`speed`)** | `1633 ms`, `926 ms`, `910 ms` | **`1156 ms`** | **-61.8% vs pre-cleanup / -76.4% vs post-cleanup** |

---

## 2. Frame Render Latency & Jank (`adb shell dumpsys gfxinfo`)

| Screen / Scenario | Frames | Janky (`>16.6 ms`) | 50th pct | 90th pct | 95th pct | 99th pct | GPU 50th pct | Slow UI / Missed Vsync / Bitmap Upload |
|---|---|---|---|---|---|---|---|---|
| **Baseline Home Run A** (12 D-pad navs, `.debug`) | `31` | `29 (93.55%)` | `600 ms` | `3400 ms` | `4000 ms` | `4400 ms` | `20 ms` | `29` / `26` / `—` |
| **Baseline Home Run B** (3 D-pad navs, `.debug`) | `10` | `6 (60.00%)` | `129 ms` | `350 ms` | `350 ms` | `350 ms` | `17 ms` | `6` / `5` / `—` |
| **Release Home Cross-Row + Billboard** (12 D-pad navs) | `136` | `35 (25.74%)` | **`26 ms`** | **`46 ms`** | **`48 ms`** | **`57 ms`** | **`16 ms`** | **`6` / `3` / `0`** |
| **Release Home Steady In-Row Focus** (10 D-pad navs) | `114` | `32 (28.07%)` | **`28 ms`** | **`34 ms`** | **`36 ms`** | **`42 ms`** | **`16 ms`** | **`4` / `0` / `0`** |
| **Release Library Smart (`Anime`)** (7 D-pad navs) | `81` | `22 (27.16%)` | **`24 ms`** | **`31 ms`** | **`42 ms`** | **`53 ms`** | **`13 ms`** | **`2` / `0` / `0`** |
| **Release Media Detail** (8 D-pad navs) | `52` | `13 (25.00%)` | **`26 ms`** | **`48 ms`** | **`53 ms`** | **`57 ms`** | `18 ms` | **`0` / `0` / `0`** |
| **Release Album Detail** (5 D-pad navs) | `20` | `8 (40.00%)` | `31 ms` | `46 ms` | `48 ms` | `48 ms` | `20 ms` | `0` / `0` / `0` |

### Analysis of the `16.6 ms` Target on Mali-G31 MP2

- **99th-percentile tail latency dropped by ~98.7% (`4400 ms -> 42–57 ms`)**: Multi-hundred-millisecond and multi-second UI stalls (`Choreographer: Skipped 69 frames`, `600–4400 ms` buckets) are completely eliminated; `Slow UI thread` is `0–6` frames across entire navigation passes and `Slow bitmap uploads` is `0` everywhere.
- **Why `Janky frames (>16.6 ms)` sits at `25%–28%` on `Chromecast HD`**: Android's `gfxinfo` jank counter marks every frame >`16.67 ms` (`60 Hz`). Because Chromecast HD pairs a single Mali-G31 MP2 GPU with a 1080p full-screen Skia OpenGL compositor, `Number Slow issue draw commands` clusters tightly around `18 ms–28 ms` even when `Slow UI thread == 0` and `Missed Vsync == 0`. Thus the strict `< 5%` sub-`16.6 ms` threshold in `PERF_BASELINE.md` is not reached on Mali-G31 MP2 hardware, while effective frame pacing holds smoothly around ~35–45 fps with zero multi-second freezes (`4950 ms` frame histogram buckets = `0` on steady in-row scrolling).

---

## 3. Memory Usage (`adb shell dumpsys meminfo`)

| Metric | Baseline Home (`.debug`, pid `7912`) | Release Home (`pid 23934`) | Release Media Detail (`pid 24357`) | Delta (Home vs Baseline) |
|---|---|---|---|---|
| **TOTAL PSS** | `192,876 kB` (~188.4 MB) | **`167,228 kB`** (~163.3 MB) | `177,066 kB` (~172.9 MB) | **`-25,648 kB` (`-13.3%`)** |
| **TOTAL RSS** | `264,000 kB` | **`219,636 kB`** | `229,964 kB` | **`-44,364 kB` (`-16.8%`)** |
| **Java Heap (Pss)** | `24,848 kB` | **`13,148 kB`** | `14,700 kB` | **`-11,700 kB` (`-47.1%`)** |
| **Native Heap (Pss)** | `27,116 kB` | **`18,808 kB`** | `23,032 kB` | **`-8,308 kB` (`-30.6%`)** |
| **Graphics / GL mtrack (Pss)** | `57,948 kB` | **`42,220 kB`** | `44,936 kB` | **`-15,728 kB` (`-27.1%`)** |
| **Attached Views (`ViewRootImpl`)** | `348` (`1` root) | **`12`** (`1` root) | `—` | **`-96.6%` view objects** |
| **Scratch RenderTarget GPU Cache** | `39.36 MB` | **`27.52 MB`** | `—` | **`-30.1%` FBO memory** |

All memory targets (`TOTAL PSS <= 221,807 kB`) are beaten with ~54 MB of headroom on Home.

---

## 4. Key Optimizations Applied

1. **Native C NDK Texture & Palette Engine (`app/src/main/cpp/cinefast.c` -> `libcinefast.so`)**:
   - Extracted dominant ambient colors from `64x64` artwork samples in C (`-O3`) instead of Kotlin/Java `Palette` allocations.
   - Pre-rendered cached `96x54` opaque (`BlendMode.Src`) ambient canvas textures (`nativeRenderAmbientCanvas`) and `64x36` premultiplied hero scrims (`nativeRenderHeroScrim`) on CPU once per item instead of evaluating full-screen 1080p `Brush.radialGradient` / `linearGradient` shaders on Mali-G31 every frame.
2. **Zero-Recomposition Focus Architecture (`CinemaComponents.kt`, `CinemaNavigationRail.kt`)**:
   - Replaced `mutableStateOf(isFocused)` + `tv.material3.Surface` containers with single `Animatable(0f)` focus drivers read exclusively in `graphicsLayer` and `drawBehind` / `drawWithContent`.
   - Removed multi-child `.clip(Shape)` modifiers inside scaled card/button containers (keeping leaf `.clip` only on `AsyncImage`), reducing `Scratch RenderTarget` FBO churn.
3. **Coil Memory & Decode Discipline**:
   - Enforced `allowRgb565(true)`, `12%` RAM / `96 MiB` disk caps, `width`/`height` downsampling, `350 ms` hero billboard focus debounce, and `crossfadeMs = 0` on full-screen Hero backdrops.
4. **R8 Shrinking + On-Device AOT (`speed`)**:
   - Minified Release APK shrank to `24.4 MB` and `cmd package compile -m speed -f org.jellyfin.androidtv` eliminated JIT warm-up pauses during D-pad navigation.
