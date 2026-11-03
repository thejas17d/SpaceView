# SpaceView: Android performance, size and Play Store readiness research

Status: research for the planning phase. No app code yet.
Date: 2 November 2026
Scope: native Kotlin + Jetpack Compose + ARCore + a Filament-based renderer (probably SceneView; another agent is choosing it). AdMob for revenue. India first, so many 3–4 GB RAM phones, slow eMMC storage and costly mobile data. Android only, on Google Play. Built with a CLI toolchain and GitHub Actions; Android Studio is not used.

Each section ends with a short "Do" list. Section 0 collects all targets and budgets in one place, and Section 10 lists the sources. Any claim not confirmed by an official source is labelled **(unverified)**.

---

## 0. Summary: targets and budgets

| Metric | SpaceView target ("top-notch") | Hard limit / Play threshold |
|---|---|---|
| Play download size, arm64 phone (bundletool `get-size total`) | **≤ 15 MB** at launch, stretch goal ≤ 12 MB; CI fails above 18 MB | Play warns mobile-data users above 200 MB. Base module max 500 MB |
| Installed size, before user data | ≤ 45 MB | — |
| Extra one-off download of Google Play Services for AR (on phones that lack it) | ~36–38 MB, not counted in our size but users see it | — |
| Cold start TTID, mid-range phone (P50) | **≤ 800 ms**; ≤ 1.5 s on a 3 GB low-end phone | Vitals counts cold ≥ 5 s, warm ≥ 2 s, hot ≥ 1.5 s as excessive |
| Time from tapping "Measure" to first tracked AR frame | ≤ 1.5 s to camera preview, plus tracking time (depends on the scene) | — |
| AR frame rate | Steady **30 fps** (camera locked to 30) with no dropped frames; UI thread frames < 16 ms | — |
| User-perceived crash rate | **< 0.3 %** overall | 1.09 % overall, 8 % per phone model |
| User-perceived ANR rate | **< 0.15 %** overall | 0.47 % overall, 8 % per phone model |
| Foreground memory (Anon RSS + swap) during AR, P99 | ≤ 700 MB on 4 GB phones; ≤ 500 MB on 3 GB phones | 2.0 GB P99 for the 4 GB tier. No threshold for phones under 4 GB, but low-memory kills hit there first |
| Bitmap memory | ≤ 100 MB | 200 MB (perceptible/background) |
| Excessive partial wake locks | 0 (use no wake locks; use `FLAG_KEEP_SCREEN_ON`) | 5 % |
| Saved gallery item (GLB + thumbnail) | ≤ 500 KB typical, ≤ 1.5 MB max | — |
| GPU texture memory per placed object | ≤ 6 MB (1024² RGBA8 with mips ≈ 5.3 MB) | — |
| Continuous AR session | Device stays below `THERMAL_STATUS_MODERATE` for at least 10 min on a mid-range phone; automatic quality step-down beyond that | — |

**Must-do items (ranked)**
1. **target SDK 36 now.** It has been required for new apps since 31 Aug 2026. Plan target 37 before Aug 2027.
2. **16 KB page size:** every `.so` (ARCore, Filament/gltfio, anything else) must have 16 KB-aligned ELF segments. Play blocks updates that fail this from **1 Feb 2027**. Add a CI check from the first commit.
3. **R8 full mode plus resource shrinking** on release builds, with narrow keep rules and **no** blanket `-keep class com.google.android.filament.** { *; }`.
4. **Baseline and Startup Profiles**, generated on CI with a Gradle Managed Device (AOSP image, KVM on ubuntu runners).
5. **Defer heavy initialisation:** no ARCore, Filament or AdMob work in `Application.onCreate`. Initialise AdMob on a background thread after UMP consent.
6. **AR session hygiene:** 30 fps camera config, depth and HDR lighting off by default, plane finding off after placement, `session.pause()` in `onPause` and `close()` in `onDestroy`, every CPU `Image` closed inside `use {}`.
7. **Thermal step-down** with `PowerManager` thermal status and headroom.
8. **No ads on the AR camera screen.** Preload the interstitial and show it only at the "scan saved" transition, with a frequency cap.
9. **Gallery stored in app-private storage** (Room + GLB/JPEG + WebP thumbnails). No media read permissions; export through MediaStore or the share sheet.
10. **CI gates:** lint, size budget (bundletool), 16 KB check, R8 `r8.json` metrics, and a scheduled Macrobenchmark startup run.

---

## 1. App size

### 1.1 Why it matters for India
- Google's own Play data (2017, still the most-cited figure) shows **each extra 6 MB lowers install conversion by about 1 %**. In emerging markets, specifically **India** and Brazil, **removing 10 MB raised conversion by about 2.5 %** ([Google Play blog, "Shrinking APKs, growing installs"](https://medium.com/googleplaydev/shrinking-apks-growing-installs-5d3fcba23ce2)).
- Play shows a non-blocking warning to mobile-data users when an app is larger than **200 MB**. Current hard limits are: base module 500 MB, each feature module 500 MB, each asset pack 1.5 GB, 4 GB total for modules plus install-time packs, and 30 GB for on-demand/fast-follow packs ([Play Console size limits](https://support.google.com/googleplay/android-developer/answer/9859372?hl=en)). SpaceView will be nowhere near these limits, so the goal is conversion, not compliance.
- AR adds a hidden cost. On phones without **Google Play Services for AR** (the ARCore runtime), the user also downloads roughly **36–38 MB** (APKMirror listings for 1.47/1.48, **unverified** for current builds). With **AR Required**, Play installs it automatically. With **AR Optional**, the app must prompt for it (§6.5). The size of our own APK is the only part we control.

### 1.2 Where the bytes go (expected composition, arm64 split)
| Component | Rough download contribution | Notes |
|---|---|---|
| Kotlin/Compose/AndroidX DEX after R8 | 2–4 MB | Compose + Material3 + Navigation + Room + DataStore |
| play-services-ads (legacy) or GMA Next-Gen | 1.5–3 MB **(unverified, measure it)** | Google says Next-Gen is "smaller on device" than the full legacy SDK. The Lite SDK has been deprecated since Jan 2026 ([Lite SDK page](https://developers.google.com/admob/android/lite-sdk?hl=en)) |
| Filament + gltfio native libs (arm64 only) | ~3–5 MB | The Filament 1.79 **AAR is 4.88 MB for all ABIs** ([Filament releases](https://github.com/google/filament/releases)). **Never ship `filamat-android`** (a 12.7 MB AAR with a runtime shader compiler). Use precompiled `.filamat` materials |
| ARCore client SDK (`com.google.ar:core`) | < 1 MB | The heavy runtime lives in Google Play Services for AR, not in our APK |
| Materials, IBL/skybox, sample models | 0.5–2 MB | Use KTX2/compressed IBL, small or no skybox (the camera is the background in AR) |
| Images, fonts, icons | < 0.5 MB | Vector drawables or WebP; no custom fonts, or download them later |

A **15 MB budget is realistic** if we avoid mediation adapters, `filamat`, Firebase bloat and bundled demo models.

### 1.3 Build settings (aligned with Gridlark: AGP 9.4.1, Gradle 9.6.1, Kotlin 2.3.21, compileSdk 37, targetSdk 36)
- **Always upload an AAB.** Play then serves per-ABI, per-density and per-language splits. Gridlark CI already builds `:app:bundleRelease`.
- **R8 full mode** has been on by default since AGP 8.0. Never add `android.enableR8.fullMode=false`. AGP 9.0 made **optimized resource shrinking** the default when `isShrinkResources = true`, and AGP 9.1 repackages classes by default ([Enable app optimization](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)).
- On **AGP 9.3+** you can switch to the new DSL, which turns on code and resource optimisation and includes the default rules (equivalent to `proguard-android-optimize.txt`):
  ```kotlin
  android { buildTypes { release { optimization { enable = true } } } }
  // keep rules go in src/main/keepRules/*.keep
  ```
  Gridlark's legacy form (`isMinifyEnabled = true`, `isShrinkResources = true`, `proguard-android-optimize.txt`) is still supported. Either is fine; pick one and use it in both projects.
- **ABIs:** ship `arm64-v8a` and `armeabi-v7a`. Some 3 GB Indian phones still run 32-bit userspace, but ARCore certification of those phones is rare, so check the Play device catalogue once the app is live. Add `x86_64` **only in debug/benchmark builds** for emulator and GMD tests. In an AAB, extra ABIs do not change what users download, but they do bloat the universal/test APKs.
  ```kotlin
  defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") } }
  buildTypes { debug { ndk { abiFilters += "x86_64" } } }
  ```
- **Native libs uncompressed** (`useLegacyPackaging = false`, the AGP default). This is required for 16 KB alignment and avoids an extracted duplicate on disk. The download stays compressed because Play compresses over the wire.
- **Resources:** `androidResources { localeFilters += listOf("en", "hi") }`. Add more Indian languages later through Play language splits. Strip unused `com.google.android.gms` locales this way too.
- **Exclude** `META-INF/*.kotlin_module`, `*.version`, `DebugProbesKt.bin` and similar in `packaging.resources.excludes`. Gridlark already excludes `AL2.0`/`LGPL2.1`.
- **Do not add mediation in v1.** Each adapter brings a full third-party ad SDK (often 2–8 MB each, **unverified**, so measure before adding), plus more Data-safety rows and more ANR surface.

### 1.4 Play Feature Delivery / on-demand modules: use sparingly
- On-demand modules need SplitCompat, network at first use, a UI for "downloading…", and testing with `bundletool --local-testing`. Feature modules cannot enable minify themselves (the base module controls R8). Exported activities in feature modules are not allowed ([Play Feature Delivery](https://developer.android.com/guide/playcore/feature-delivery)).
- **AR is SpaceView's core feature.** Putting ARCore and Filament on-demand would make every new user wait through a second download before the first measurement, which is a worse UX than 3–5 MB extra at install. **Recommendation: AR/3D rendering stays in the base module in v1.**
- Candidates for on-demand delivery later:
  - an **ML segmentation/edge-refinement model** (e.g. 3–10 MB TFLite/LiteRT) for better scan cutouts. SceneView's own release notes mention a ~10 MB on-device detector that is "not in the APK" and is downloaded on first use ([SceneView releases](https://github.com/sceneview/sceneview-android/releases));
  - a **furniture/sample model catalogue.**
- **Conditional delivery** (`<dist:device-feature dist:name="android.hardware.camera.ar"/>`) only makes sense if we choose **AR Optional** and also want the 3D viewer without Filament. We don't, because the gallery's 3D preview needs Filament too.

### 1.5 Play Asset Delivery (PAD) for 3D assets
- PAD works for apps as well as games. *On-demand* packs do not count toward the listed app size; *install-time* packs do ([Play Asset Delivery](https://developer.android.com/guide/playcore/asset-delivery)). Use **fast-follow** for a small "starter furniture" pack and **on-demand** for larger catalogues. Assets in packs are read through `AssetPackManager` file paths, so no extra copy is made.
- v1 has no catalogue (the gallery is user-generated), so **PAD is not needed for v1.** Plan for it once more than ~2 MB of bundled models exists.

### 1.6 Model and texture compression
| Asset kind | Format | Tooling |
|---|---|---|
| **Bundled/shipped models** (samples, UI gizmos) | GLB + **EXT_meshopt_compression** + **KHR_mesh_quantization** + **KTX2 (KHR_texture_basisu)**: UASTC for normal/ORM maps, ETC1S for base colour | `gltf-transform optimize in.glb out.glb --compress meshopt --texture-compress ktx2 --texture-size 1024` (needs KTX-Software ≥ 4.4) ([glTF Transform](https://gltf-transform.dev/)) |
| **User-scanned objects (created on the device)** | GLB containing a box or low-poly mesh (tens to hundreds of vertices, so no mesh compression needed) and **one JPEG texture atlas** (1024×1024, q≈80, ~150–350 KB) | Encode on the device with `Bitmap.compress(JPEG)`. **Do not** encode KTX2/Basis on the device: it is slow, CPU-heavy and needs an extra native encoder library |
| Thumbnails | **WebP** (lossy, q≈75, 256–384 px longest side, ~10–25 KB) | `Bitmap.compress(WEBP_LOSSY)` |
| UI images | Vector drawables or WebP | AGP/aapt |

- Filament gltfio supports **KHR_texture_basisu, EXT_meshopt_compression, KHR_mesh_quantization, KHR_draco_mesh_compression and KHR_texture_transform** ([Filament README](https://github.com/google/filament)). Prefer **meshopt over Draco** because it decodes faster and needs a smaller decoder. Draco support is described as "platform-dependent".
- **gltfio does not list EXT_texture_webp**, so textures inside GLBs must be JPEG/PNG (or KTX2). WebP is for thumbnails only.
- Check that the chosen SceneView version passes a KTX2 provider through to gltfio. This is a question for the rendering agent.

**Do:** AAB only; R8 full + resource shrink; arm64 + v7a; `filamat` banned; no mediation; meshopt + KTX2 for shipped assets; JPEG atlas in GLB for user scans; a size budget enforced in CI (§7.5).

---

## 2. Startup and runtime performance

### 2.1 Baseline and Startup Profiles without Android Studio
- Baseline Profiles precompile the hot paths. **Startup Profiles** also reorder DEX so startup code sits in `classes.dex`, which is "usually **15–30 % faster** than Baseline Profiles alone". They need R8 enabled and AGP ≥ 8.3 for DEX layout optimisation (on by default) ([Startup Profiles](https://developer.android.com/topic/performance/baselineprofiles/dex-layout-optimizations)).
- Generate them entirely from Gradle with a **Gradle Managed Device**. The image must be **AOSP** because the generator needs root ([Configure Baseline Profile generation](https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles)):
  ```kotlin
  // :baselineprofile (com.android.test + androidx.baselineprofile plugin)
  android {
    testOptions.managedDevices.allDevices {
      create<com.android.build.api.dsl.ManagedVirtualDevice>("pixel6Api34") {
        device = "Pixel 6"; apiLevel = 34; systemImageSource = "aosp"
      }
    }
  }
  baselineProfile { managedDevices += "pixel6Api34"; useConnectedDevices = false }
  // :app
  baselineProfile { saveInSrc = true; mergeIntoMain = true }   // commit the generated profile
  ```
  Run `./gradlew :app:generateBaselineProfile` (or `generateReleaseBaselineProfile`). Output goes to `src/main/generated/baselineProfiles/{baseline-prof.txt,startup-prof.txt}`. In the generator, call `rule.collect(packageName, includeInStartupProfile = true) { … }` for the launch journey.
- **CI plan:** a separate GitHub Actions workflow (`workflow_dispatch` plus weekly cron, or on `v*` tags) on `ubuntu-latest` with **KVM enabled** through the usual udev rule. GitHub's Linux runners support hardware-accelerated emulators ([android-emulator-runner README](https://github.com/ReactiveCircus/android-emulator-runner)). The workflow regenerates profiles and opens a PR with the diff. Do **not** use `automaticGenerationDuringBuild = true` in the normal build, because it doubles build time.
- **Journeys to profile:** cold launch → home; home → gallery grid scroll; gallery → 3D viewer. AR camera screens **cannot** run on an emulator (an ARCore emulator needs a special virtual scene), so profile up to "AR screen composed", not tracking itself.
- The compiled profile must be under 1.5 MB. Keep journeys focused.
- Gridlark already depends on `androidx.profileinstaller`. SpaceView should do the same, so sideloaded/internal builds also get the profile.

### 2.2 Startup architecture
- **`Application.onCreate` should be close to empty.** Lazy-initialise everything: Room DB on first use, AdMob after consent on a background thread (§5), ARCore availability check deferred (below), Filament Engine created only on entering a 3D/AR screen.
- Use the **App Startup** library instead of content providers for any SDK that insists on auto-init. Remove auto-init providers we don't need with `tools:node="remove"` (e.g. WorkManager initialiser if unused) ([Launch time guide](https://developer.android.com/topic/performance/vitals/launch-time)).
- Use the **SplashScreen API** (`core-splashscreen`, as in Gridlark). No custom splash activity.
- **ARCore availability:** Google recommends calling `ArCoreApk.checkAvailabilityAsync()` *early and ignoring the result* so a cached answer is ready when the AR button is shown ([Enable ARCore](https://developers.google.com/ar/develop/java/enable-arcore)). Do this **after the first frame** (`Choreographer`/`LaunchedEffect` on the home screen), not in `Application`.
- Report TTFD with `ReportDrawnWhen { galleryLoaded }` so Macrobenchmark and vitals measure the real "usable" time.
- **Targets:** cold TTID ≤ 800 ms on mid-range, ≤ 1.5 s on 3 GB phones. Vitals thresholds are cold ≥ 5 s, warm ≥ 2 s and hot ≥ 1.5 s ([vitals launch time](https://developer.android.com/google/play/vitals/launch-time)).

### 2.3 Compose performance
- **Strong skipping is on by default** since Kotlin 2.0.20, so we're covered. Unstable parameters can still skip by instance equality, and lambdas are auto-remembered ([Strong skipping](https://developer.android.com/develop/ui/compose/performance/stability/strongskipping)). We still want:
  - `@Immutable`/`@Stable` on UI models, or `kotlinx.collections.immutable` for lists;
  - a `stabilityConfigurationFiles` entry for domain types from non-Compose modules (e.g. `java.time.*`, our `Dimensions` value class);
  - Compose compiler reports behind a Gradle property, checked occasionally.
- **Lazy grids** for the gallery: `key = { it.id }` and `contentType`. Thumbnails load through **Coil 3** with explicit `size()`, which avoids decoding full-size images.
- **Defer state reads:** use lambda modifiers (`Modifier.offset { }`, `graphicsLayer { }`, `drawBehind`) for anything that animates per frame.
- **The AR overlay is the main jank risk.** Do *not* push per-frame AR data (pose, distance, tracking state) into Compose state at 30–60 Hz:
  - draw reticles and measurement lines **in the 3D renderer**, not in Compose;
  - expose a `StateFlow` throttled to about 10 Hz with `distinctUntilChanged()` (e.g. distance rounded to 1 mm) for labels, collected with `collectAsStateWithLifecycle()`;
  - use `snapshotFlow`/`conflate()` for any reverse direction.
- Always benchmark **release-like builds** (`benchmarkRelease`). Debug Compose is much slower.

### 2.4 Threading and the AR frame loop
- ARCore's `session.update()` and rendering run on the **GL/render thread** (SceneView manages this). That thread must never do disk or network I/O, Bitmap compression, Room queries or JSON parsing.
- **Do heavy work off the render and main threads:**
  - YUV→RGB conversion, texture atlas building, JPEG/WebP encode, GLB writing: use `Dispatchers.Default.limitedParallelism(2)`. Limiting parallelism stops scan processing from starving ARCore's VIO tracking threads. ARCore logs **"VIO frequency low"** when CPU starvation hurts tracking ([ARCore performance](https://developers.google.com/ar/develop/performance));
  - Room and file I/O: use `Dispatchers.IO`.
- **CPU camera images:** the default CPU stream is **640×480 and costs nothing extra**. Any higher resolution adds a second stream, which costs real CPU/memory ([ARCore ML guide](https://developers.google.com/ar/develop/java/machine-learning)). For photo textures, capture **one** higher-resolution frame only when the user presses "Capture". Don't stream it.
- **Always close camera images:** `frame.acquireCameraImage().use { … }`. Treat `NotYetAvailableException` as "try the next frame". Holding images causes `ResourceExhaustedException`. Copy the planes out quickly and process the copy on a worker thread.
- Use `CameraConfigFilter.setTargetFps(EnumSet.of(TARGET_FPS_30))` and `setDepthSensorUsage(DO_NOT_USE)` unless depth is needed. 60 fps "increases power consumption and memory usage and is not supported on all devices", and **30 fps is available on every ARCore device** ([CameraConfig.TargetFps](https://developers.google.com/ar/reference/java/com/google/ar/core/CameraConfig.TargetFps)).

### 2.5 GPU and rendering budget (renderer-agnostic, Filament-centric)
- Render at camera rate (30 fps). Don't render extra frames when nothing changes in the non-AR 3D viewer: render on demand, or pause the Choreographer callback when idle.
- **Low tier** (≤ 4 GB RAM or Mali-G52-class GPU): no SSAO, no bloom, FXAA or no AA (no MSAA), one directional light with a ≤ 1024 shadow map (or a fake contact-shadow blob), dynamic resolution allowed down to 0.75.
- Light estimation: **AMBIENT_INTENSITY** on the low tier; **ENVIRONMENTAL_HDR** only on the high tier, because HDR cubemap estimation costs CPU/GPU.
- Plane finding: **HORIZONTAL** (add vertical only for wall measuring). **Disable plane finding once an object is placed.** Disable **Instant Placement** once full tracking exists. ARCore lists both as CPU-heavy ([ARCore performance](https://developers.google.com/ar/develop/performance)).
- Depth API (over 88 % of active devices support it, per Google as of May 2026): useful for measurement on non-planar surfaces and for occlusion, but expensive. Make it **opt-in or automatic only on the high tier**, and turn it off under thermal pressure.
- Choose the tier with `ActivityManager.isLowRamDevice()`, `MemoryInfo.totalMem`, `memoryClass` and the GPU renderer string, all cached in DataStore on first launch.

### 2.6 Memory on low-RAM phones
- Vitals now has **memory core vitals**: Anon RSS + swap P99 per RAM tier (4 GB tier, foreground: 2.0 GB) and bitmap memory (200 MB). Play may reduce visibility for apps over these thresholds **from February 2027** ([Android vitals](https://developer.android.com/topic/performance/vitals); [memory usage](https://developer.android.com/google/play/vitals/memory-usage)). Phones below 4 GB have no threshold, but they are where low-memory kills happen. Watch the **LMK** vital.
- **Budget** for an AR session on a 3–4 GB phone: ≤ 500–700 MB. The main consumers:
  - camera buffers (owned by ARCore);
  - Filament: an engine with ~5–20 MB of buffers plus textures. Each 1024² texture with mips ≈ 5.3 MB of GPU memory; a 2048² one ≈ 21 MB. **Cap user textures at 1024²** on the low tier and **limit simultaneous placed objects** (e.g. 6 on the low tier);
  - an AdMob banner WebView (tens of MB; **destroy, not just hide**, banners before entering AR).
- Respond to `onTrimMemory(TRIM_MEMORY_UI_HIDDEN / BACKGROUND)`: release the Filament scene and textures and clear the Coil memory cache.
- **Filament objects are native and not garbage-collected.** Every `Texture`, `MaterialInstance`, `Entity`, `IndirectLight` and `Engine` must be destroyed explicitly. LeakCanary won't see these leaks, so check with `adb shell dumpsys meminfo <pkg>` before and after 10 cycles of entering and leaving the 3D screen.
- **ARCore session lifecycle:** create once per AR screen; `pause()` in `onPause` (frees the camera); `close()` when the screen is destroyed. Avoid fast create/destroy loops; older ARCore versions leaked memory on repeated session creation (EasyAR notes for ARCore 1.13, **unverified for current versions**).
- Don't retain `Activity`, `View` or `Session` in ViewModels. Hold AR state in a screen-scoped holder that's disposed through `DisposableEffect`/lifecycle observers.

**Do:** GMD-generated Baseline and Startup profiles; empty `Application.onCreate`; throttled AR→Compose state; 30 fps, depth off, plane finding off after placement; device tiering; explicit Filament destruction; close every `Image`.

---

## 3. Storage design (AR Gallery)

### 3.1 Layout (app-private, so no storage permissions)
```
filesDir/gallery/<uuid>/model.glb      // box/low-poly mesh + 1024² JPEG atlas, 150–500 KB
filesDir/gallery/<uuid>/thumb.webp     // 384 px, ~15–25 KB
filesDir/gallery/<uuid>/source.jpg     // OPTIONAL original capture, off by default (saves space)
cacheDir/capture/…                      // temp frames, deleted after the save completes
```
- **Room** (as in Gridlark: Room 2.8.x + KSP, exported schemas) holds metadata: id, name, width/height/depth in mm (store integers in **millimetres**, never floats in cm), created/modified, file sizes, thumbnail path, schema version, measurement accuracy/confidence.
- **DataStore** (Preferences) holds settings: units, device tier cache, consent-derived flags, ad frequency counters.
- **Writes are atomic.** Write to `model.glb.tmp`, `fsync`, then rename, then insert the Room row in a transaction. On startup, a sweep deletes orphan directories and rows whose files are missing.
- **Scoped storage:** no `READ_MEDIA_IMAGES` or `WRITE_EXTERNAL_STORAGE`. Play's Photo & Video policy allows broad media permissions only for apps whose *core* purpose is broad media access. Everyone else must use the **Photo Picker** ([Photo & Video Permissions policy](https://support.google.com/googleplay/android-developer/answer/14115180)). To import a photo, use `PickVisualMedia`. To export, use **MediaStore** inserts into `Pictures/SpaceView` (no permission needed on API 29+) and `FileProvider` + share sheet for `.glb`.
- **Before saving:** check `StorageManager.getAllocatableBytes()` and show a clear "storage full" message. Many Indian low-end phones run close to full.
- **Cache eviction:** `cacheDir/capture` is cleared after every scan and on launch; Coil disk cache ≤ 50 MB; never put gallery data in `cacheDir`, because the OS may delete it.
- **Backup:** Auto Backup is capped at 25 MB per app. Either exclude `gallery/` *and* the Room DB together in `dataExtractionRules` (so no rows point at missing files), or include both and accept partial restore. **Recommended for v1:** exclude both, and offer an explicit "Export all" (.zip) later.
- **Image pipeline:** capture frame → crop/rectify each face on a worker thread → downscale with `Bitmap.createScaledBitmap` or `inSampleSize` to ≤ 1024 px per face/atlas → recycle intermediates → JPEG q80 into the GLB. Display thumbnails with Coil (`size(256)`). Use `Bitmap.Config.HARDWARE` for read-only display bitmaps to keep them out of the Java heap.
- **Approximate storage per scan:** 0.2–0.5 MB, so 100 scans ≈ 20–50 MB. Show total gallery size in Settings.

**Do:** private storage, Room in millimetres, atomic writes, no media permissions, a consistent backup policy, an allocatable-bytes check.

---

## 4. Battery and thermal behaviour in long AR sessions
- AR keeps the camera, VIO/SLAM, GPU and display busy all at once. On low-end SoCs, a session can reach `THERMAL_STATUS_MODERATE` within minutes.
- **Monitoring** ([ARCore performance](https://developers.google.com/ar/develop/performance); [ADPF thermal](https://developer.android.com/games/optimize/adpf/thermal)):
  - `PowerManager.addThermalStatusListener` (API 29+) and `getThermalHeadroom(forecastSeconds)` (API 30+). Call headroom **at most once every 10 s** (more often returns NaN). A NaN or 0 first value means the API isn't supported.
  - Step-down ladder: headroom ≥ 0.85 or MODERATE → disable Depth and HDR light estimation, dynamic resolution to 0.75, shadows off. Headroom ≥ 0.95 or SEVERE → pause the AR session and show "Phone is warm, AR paused". CRITICAL → end the session.
- **Idle auto-pause:** if there's no touch and the reticle hasn't moved for about 60 s, pause the session and show "Tap to resume". That saves battery, and the camera LED/indicator turns off.
- Use **`FLAG_KEEP_SCREEN_ON` on the AR window only.** Never use partial wake locks. "Excessive partial wake locks" is a core vital with a 5 % threshold ([Android vitals](https://developer.android.com/topic/performance/vitals)).
- No background work during or after AR. No WorkManager jobs are needed in v1.
- Set the display refresh rate preference for the AR screen to 60 Hz (`preferredDisplayModeId`/`Surface.setFrameRate(30f, …)` on the render surface). This stops 120 Hz panels burning power on a 30 fps camera feed.
- **Test protocol:** a 15-minute continuous session on a 4 GB MediaTek Helio G-series phone and a Snapdragon 6-series phone. Log thermal status, fps, battery % and dropped frames, and record a Perfetto trace. Google's ADPF guidance recommends sessions of at least 15 minutes to see thermal stabilisation.

---

## 5. AdMob performance and compliance

### 5.1 SDK choice
- **Legacy** `play-services-ads` 25.x (Gridlark uses 25.5.0 + UMP 4.0.0): mature and supports all mediation. Since 24.0.0, `OPTIMIZE_INITIALIZATION` and `OPTIMIZE_AD_LOADING` are **on by default** ([Optimize initialization](https://developers.google.com/admob/android/optimize-initialization)).
- **GMA Next-Gen SDK** (`com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk`, 1.6.0 on the quick-start page):
  - minSdk 24;
  - smaller on device than the legacy SDK (per Google);
  - built-in **preloading API**: call `start` once, then `pollAd()`; the SDK refills and retries automatically; at most 6 preloaded ads in total; a buffer of 2 per preload ID is recommended ([Next-Gen ad preloading](https://developers.google.com/admob/android/next-gen/ad-preloading));
  - callbacks arrive on a **background thread**, so UI work must be posted to the main thread;
  - mediation only works through AdMob mediation.
  - Third-party posts say it reached 1.x in April 2026 and became Google's "preferred" Android SDK in July 2026 **(unverified)**.
- **Recommendation:** choose **Next-Gen** for SpaceView if the official page shows it as GA when implementation starts. We have no mediation in v1, size matters, and preloading fits the "interstitial after scan" pattern. Otherwise use legacy 25.x to match Gridlark. Either way, keep the ads behind a small `AdsGateway` interface so they can be swapped.

### 5.2 Initialisation order (no main-thread blocking)
1. First frame drawn (home screen).
2. Call UMP `requestConsentInfoUpdate` on **every launch**. Show the form if needed. `canRequestAds()` returns false until the update has been called ([UMP privacy](https://developers.google.com/admob/android/privacy)).
3. When `canRequestAds()` is true, call `MobileAds.initialize()` **on a background thread** (`Dispatchers.IO`), once per process. The Next-Gen docs warn that initialising on the main thread may cause an ANR ([Next-Gen quick start](https://developers.google.com/admob/android/next-gen/quick-start)).
4. If there's no mediation, `MobileAds.disableMediationAdapterInitialization()` (legacy) skips adapter start-up work.
5. Start preloading the interstitial **when the user enters the scan flow**, not at app start. That saves data and memory on users who only browse.

### 5.3 Placement rules (performance + policy)
- **No ads on the AR camera screen**, neither banner nor interstitial, during tracking:
  - a banner WebView costs memory and GPU composition on top of the camera surface;
  - it invites accidental clicks;
  - it competes with VIO for CPU.
  Destroy the banner `AdView` when navigating into AR and recreate it afterwards.
- **Interstitial "per scan"** is shown at the *transition after "Saved to gallery"*, never in the middle of an AR session and never on app open/exit. AdMob explicitly disallows interstitials on app load or exit, after every user action, immediately after another interstitial, or ones that launch unexpectedly ([AdMob interstitial guidance](https://support.google.com/admob/answer/6066980?hl=en)). A completed scan is a reasonable natural break.
  - **Add a frequency cap anyway:** first scan of the session ad-free, then at most one per 3 minutes and at most one per 2 scans. This protects ratings and AdMob account health.
- **Only show a preloaded ad.** If none is ready, skip it. Never show a spinner while waiting for an ad.
- **Adaptive anchored banners** go only on gallery and list screens. Pause them in `onPause`.
- Mute video ads (`MobileAds.setAppMuted(true)` / Next-Gen equivalent) to save bandwidth and avoid annoyance.
- **Data use:** each banner refresh costs mobile data, so use the default 60 s refresh or longer, not 30 s.

### 5.4 Consent and privacy for India
- UMP messages are legally required for the **EEA/UK/Switzerland** (GDPR) and US state-privacy regimes. India is not a UMP-message region. **Still ship UMP:** it's already in Gridlark, Play distribution may extend beyond India, and it provides the privacy-options entry point.
- **DPDP Act 2023 / DPDP Rules 2025:**
  - the Rules were notified on **14 Nov 2025** with phased commencement;
  - notice, consent, security safeguards, breach reporting, data principal rights and **children's data** rules apply **18 months later (~13–14 May 2027**; law-firm sources differ by a day) ([PIB/MeitY doc](https://static.pib.gov.in/WriteReadData/specificdocs/documents/2025/nov/doc20251117695301.pdf); [The Week](https://www.theweek.in/wire-updates/business/2025/11/14/del148-biz-dpdp-rules-ld-govt.html)).
  - **Implications for planning:**
    1. Keep all scan images and measurements **on the device** (not collected). This makes SpaceView a very small data fiduciary.
    2. Publish a clear privacy notice in English and Hindi before launch.
    3. The DPDP Act treats anyone **under 18** as a child and bars tracking, behavioural monitoring and targeted advertising directed at children. Set the Play target audience to 18+ (or 13+ without child-directed content), and plan for non-personalised ads (`npa`/Limited Ads, or a "tag for under age of consent") if child users are possible. **Legal review needed before May 2027.**
- **Advertising ID:** `play-services-ads` merges `com.google.android.gms.permission.AD_ID`. Complete the Play Console Advertising ID declaration (required for target 33+).

**Do:** Next-Gen (if GA) or legacy 25.x; init after consent, off the main thread; preload at scan-flow entry; no ads in AR; frequency cap; no mediation in v1; DPDP-ready privacy notice by May 2027.

---

## 6. Play Store requirements (as of November 2026)

### 6.1 Target API level
- **New apps and updates must target Android 16 (API 36) from 31 Aug 2026.** Existing apps must target ≥ 35 to stay visible to new users on newer Android versions ([Target API requirements](https://developer.android.com/google/play/requirements/target-sdk)). Expect **API 37 to be required around Aug 2027**. compileSdk 37 is already in Gridlark and is required by SceneView 4.65.
- **Behaviour changes to plan for at target 36:**
  - **Edge-to-edge is enforced** (since target 35). Use Compose `WindowInsets` and place AR controls in safe areas.
  - **Orientation and resizability:** on large screens (smallest width ≥ 600 dp), target 36 **ignores** `screenOrientation`, `resizeableActivity` and min/max aspect ratio. An opt-out exists at API 36 only; there is **no opt-out at API 37** ([Android 16 orientation changes](https://android-developers.googleblog.com/2025/01/orientation-and-resizability-changes-in-android-16.html)).
    - The AR screen must therefore handle rotation and resizing on tablets and foldables. Update ARCore display geometry on configuration change and avoid recreating the session on rotation (`configChanges` with a carefully handled `onConfigurationChanged`, or a ViewModel-held session).
    - Phones under 600 dp can still lock portrait.
  - Predictive back is enabled by default for target 36. Make sure the AR screen's back handling (e.g. "discard measurement?") uses `PredictiveBackHandler`/`BackHandler`.

### 6.2 16 KB memory page size (critical for ARCore + Filament)
- **Rule:** apps targeting Android 15+ must support 16 KB pages on 64-bit devices. **From 1 Feb 2027, Play blocks updates that don't** ([16 KB page sizes guide](https://developer.android.com/guide/practices/page-sizes)). Without it, the app fails or runs in "page size compat mode" with a warning dialog on 16 KB devices (Pixel 8+ dev option, newer OEM phones).
- **Requirements:**
  - AGP ≥ 8.5.1 (we have 9.4) with **uncompressed** native libs, which zip-aligns them to 16 KB;
  - every `.so` with LOAD segments aligned to 2**14 and RELRO correct;
  - NDK r28+ for anything we compile ourselves (16 KB by default).
- **ARCore:** Unity's ARCore plugin changelog says ARCore **1.48** fixed 16 KB alignment for its binaries (inferred, **unverified** from Google's own notes). Current is **1.57.0** (16 Oct 2026), which targets API 37 ([ARCore SDK releases](https://github.com/google-ar/arcore-android-sdk/releases)). Use the latest.
- **Filament / SceneView:** Filament's release notes don't mention 16 KB, but the `flutter_sceneview` 4.24 changelog lists "Google Play 16 KB page-size alignment for Filament native libs" fixes ([flutter_sceneview changelog](https://pub.dev/packages/flutter_sceneview/changelog)). The latest is SceneView **4.65.0** (1 Nov 2026), with Filament 1.79.0 (26 Oct 2026). **Verify in CI. Don't trust the changelogs alone.**
- **CI check (Linux runner, no Studio needed):**
  ```bash
  $ANDROID_HOME/build-tools/36.0.0/zipalign -c -P 16 -v 4 app-release.apk   # "Verification successful"
  # plus, for every lib/arm64-v8a/*.so and lib/x86_64/*.so:
  llvm-objdump -p lib.so | grep LOAD        # every 'align 2**14' (or higher)
  # or simply: check_elf_alignment.sh app-release.apk  (AOSP script) -> all ALIGNED
  bundletool dump config --bundle=app.aab | grep -i alignment   # expect PAGE_ALIGNMENT_16K
  ```
  Also run the app on a **16 KB emulator image** in the GMD job (`Google APIs Experimental 16 KB Page Size` x86_64 system image).

### 6.3 Data safety form (planned answers; confirm at submission)
| Data | Collected/shared by | Purpose |
|---|---|---|
| IP / approximate location, app interactions, diagnostics, device or other IDs (Advertising ID, app set ID) | **AdMob**: collected **and** shared, encrypted in transit | Advertising, analytics, fraud prevention ([AdMob data disclosure](https://developers.google.com/admob/android/privacy/play-data-disclosure)) |
| Crash logs, diagnostics | Crashlytics/Sentry, *if added* | App functionality / analytics |
| Photos and camera frames, measurements, 3D models | **Not collected.** Processed and stored only on the device, never sent off it | — |
- Mark "data can be deleted" (uninstall / clear the gallery). Link a privacy policy URL (Gridlark already wires `PRIVACY_POLICY_URL` from CI secrets; reuse that pattern).

### 6.4 Camera permission
- Request `CAMERA` at runtime **only when the user taps Measure or Scan**, with a short pre-prompt explaining why. Don't request it at launch. Handle "Don't ask again" with a Settings deep link. Permission denials are a vital ([vitals list](https://developer.android.com/topic/performance/vitals)).
- No microphone, location or media permissions. Keep the manifest minimal and check it in CI by comparing the merged manifest's permission list.

### 6.5 AR Required vs AR Optional
| | AR Required | AR Optional |
|---|---|---|
| Manifest | `<uses-feature android:name="android.hardware.camera.ar"/>` + `<meta-data android:name="com.google.ar.core" android:value="required"/>` | `<meta-data … android:value="optional"/>`, **no** `camera.ar` feature |
| Play visibility | **Only ARCore-certified devices** | All compatible devices |
| Installs Google Play Services for AR | Automatically, with the app | No. The app must call `requestInstall()` when AR is first used |
| minSdk | 24 | 24 for AR features |
Source: [Enable ARCore](https://developers.google.com/ar/develop/java/enable-arcore).

**Recommendation: AR Required for v1.**
- Every core feature (measure, scan, place) needs ARCore. On a non-certified phone, an AR Optional install could only browse an empty gallery. That produces 1-star reviews, refunds of attention, and uninstalls, which hurt Play ranking.
- AR Required also removes the in-app "install ARCore" step (one less failure point and one less ANR risk).
- **Cost:** reach is limited to ARCore-certified models. Many sub-₹10k phones are not certified (download the device CSV from the Play Console device catalogue and check top Indian models, such as Redmi, Realme, Samsung A/M and Moto G series, before launch).
- **Revisit later:** switch to AR Optional if a non-AR feature is added, such as manual photo-based measurement or a pure 3D gallery.
- Even with AR Required, still call `checkAvailability` and `requestInstall` before creating the session, because users can sideload or have an outdated ARCore. Handle `UnavailableUserDeclinedInstallationException` cleanly.

### 6.6 Android vitals (what Play enforces)
- **Core vitals:**
  - user-perceived crash rate: **1.09 %** overall, **8 %** per phone model;
  - user-perceived ANR rate: **0.47 %** overall, **8 %** per phone model;
  - excessive partial wake locks: **5 %**;
  - memory: Anon RSS + swap P99 per RAM tier, and bitmap memory.
  Memory and **DEX code optimisation** thresholds may affect visibility **from Feb 2027**. The DEX vital applies only to apps with **> 10 MB DEX**, which we won't reach, but enabling R8 covers it anyway ([Android vitals](https://developer.android.com/topic/performance/vitals); [DEX code optimisation](https://developer.android.com/google/play/vitals/code-optimization)).
- Play uses a 28-day rolling window. Set up email alerts and use the **Play Developer Reporting API** if we automate dashboards.
- AR-specific crash and ANR risks:
  - GPU driver crashes in Filament on specific Mali/PowerVR devices: watch per-device rates and exclude broken models in the device catalogue if needed;
  - ANRs from synchronous ARCore install checks or session creation on the main thread: create the session off the main thread where the API allows, and never block the UI on `checkAvailability`;
  - the main thread stalling on GLB writes.

### 6.7 Pre-launch reports, testing tracks, Play Integrity, in-app review/updates
- **New personal developer accounts** (created after 13 Nov 2023) must run a **closed test with ≥ 12 testers opted in for 14 continuous days** before production access. This is per new app ([Play Console help](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en-GB)). Build this into the timeline. Organisation (D-U-N-S) accounts are exempt.
- **Pre-launch reports** run automatically on uploads to testing tracks (Robo crawler on real devices). The AR screen will only show a camera feed. Provide a Robo script or test account path that exercises gallery, settings and the 3D viewer, and treat its crash, ANR, accessibility and security findings as release blockers.
- **Play Integrity:** **not needed for v1.** There's no server, no paid content, and nothing worth protecting. If added later, use the **standard request** with `prepareIntegrityToken` at a quiet moment, not on the startup path; preparation is limited to 5 times per minute per app instance ([Standard requests](https://developer.android.com/google/play/integrity/standard)).
- **In-app review** (`review-ktx`, as in Gridlark): ask after the user's **third successful saved scan**, never from a button. Quota is enforced and the dialog may not show ([In-App Review](https://developer.android.com/guide/playcore/in-app-review)).
- **In-app updates:** use the *flexible* flow for normal releases and *immediate* only for critical fixes. Check at most once per day, after first frame, never while in AR.

---

## 7. Quality tooling

### 7.1 R8 keep rules
- First rely on **consumer rules shipped in the AARs**: ARCore, play-services-ads, Room, Compose and Coil all ship them. Add our own rules only after a release-build crash names the missing class.
- **Filament/gltfio/SceneView:**
  - JNI entry points are kept by Filament's native-method rules; check whether the SceneView AAR ships `consumer-rules.pro` (**unverified**; ask the rendering agent to check).
  - If R8 breaks JNI callbacks, keep only the classes/methods called *from* native code (e.g. `-keepclasseswithmembernames class * { native <methods>; }` is already in the default rules).
  - **Avoid `-keep class com.google.android.filament.** { *; }`.** Broad package keeps stop most optimisation, and they show up in the R8 Configuration Analyzer ([R8 keep rules blog](https://developer.android.com/blog/posts/configure-and-troubleshoot-r8-keep-rules)).
- **kotlinx.serialization** (if used for metadata) needs the official rules, which the plugin now ships.
- Keep the `mapping.txt` upload in CI (Gridlark already archives it). Upload it to Play automatically with the release, so vitals stack traces are deobfuscated. Upload **native debug symbols** where available.
- Use the **R8 Configuration Analyzer** (AGP ≥ 9.3) and inspect `BUNDLE-METADATA/com.android.tools/r8.json` from the AAB to track the optimisation, obfuscation and shrinking percentages Play sees ([DEX code optimisation](https://developer.android.com/google/play/vitals/code-optimization)).
- **Test the release build on a real device every sprint.** R8 bugs only appear in release builds.

### 7.2 LeakCanary and StrictMode (debug only)
- **LeakCanary:** `debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")` (stable). 3.0 is still alpha (3.0-alpha-9), so don't use it. LeakCanary catches Activity, Fragment, ViewModel and View leaks. It **won't** catch Filament or ARCore native leaks (see §2.6 meminfo protocol).
- **StrictMode** in debug `Application`: `ThreadPolicy.detectDiskReads/Writes/Network().penaltyLog()` and `VmPolicy.detectLeakedClosableObjects().detectActivityLeaks().detectLeakedRegistrationObjects().detectUnsafeIntentLaunch()`. Expect some noise from the AdMob and ARCore SDKs; allowlist them by stack.
- `debugImplementation` only. Nothing debug-only may reach release (lint and the dependency check enforce this).

### 7.3 Crash reporting: Crashlytics vs lighter options
| Option | Size | Pros | Cons |
|---|---|---|---|
| **Play Console Android vitals** (built in) | 0 | Free; counts crashes before SDK init; native crashes with uploaded symbols; this is the metric Play actually ranks on | Only Play installs from users who opted in to diagnostics; few breadcrumbs; ~1-day delay |
| **Firebase Crashlytics** | Small core (Google claims ~40 KB, **unverified**) plus Firebase common/transport deps; NDK variant adds `.so` | Custom keys (GPU, ARCore version, tier), breadcrumbs, non-fatals, near-real-time, free | Adds the Google Services plugin/`google-services.json`; more Data-safety rows; init cost at startup |
| Sentry | ~1 MB (PayU measurement, **unverified/dated**) | Rich, self-hostable | Heavier; paid at scale |
| ACRA | Small | Self-hosted, no Google dependency | You run a backend; limited UI |

**Recommendation:**
- **Launch with Play vitals plus `ApplicationExitInfo`** (API 30+, logged locally and shown in a debug screen). This adds zero size and no Data-safety change.
- Add **Crashlytics (non-NDK)** in the first update *if* vitals shows device-specific GPU/AR crashes we can't diagnose. Its custom keys (GPU renderer string, ARCore APK version, device tier) are the main reason to adopt it.
- Gridlark has no crash SDK either, so this keeps the two projects consistent.

### 7.4 Profiling without Android Studio
- **Perfetto:** `adb shell perfetto` with the `atrace`/`gfx`/`view`/`camera`/`power` categories, or the on-device System Tracing app. Open traces at ui.perfetto.dev. Look for `Choreographer#doFrame`, `Compose:recompose` and the RenderThread/GL thread slices ([launch-time guide](https://developer.android.com/topic/performance/vitals/launch-time)).
- **Startup:** `adb shell am start -S -W <pkg>/.MainActivity` gives TotalTime (TTID) quickly. Use Macrobenchmark `StartupTimingMetric`/`FrameTimingMetric` for repeatable numbers.
- **Memory:** `adb shell dumpsys meminfo <pkg>`; `dumpsys gfxinfo <pkg> framestats`.
- **ARCore:** `adb logcat | grep "VIO frequency low"`.
- Android also now ships an **Android CLI with agent "skills"** (e.g. `android skills add r8-analyzer`) that runs without the IDE ([Enable app optimization](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)). Worth evaluating for this IDE-less workflow.

### 7.5 CI checks (extending Gridlark's `android.yml`)
Gridlark's workflow already runs tests, `lintDebug` (`abortOnError = true`), `assembleDebug`, release APK+AAB, and archives the mapping. Add these for SpaceView:
1. `:app:lintRelease`, which includes the 16 KB alignment lint check for native libraries.
2. **Size budget:**
   ```bash
   bundletool build-apks --bundle=app-release.aab --output=app.apks --device-spec=ci/arm64-xxhdpi-api30-en.json
   bundletool get-size total --apks=app.apks --device-spec=ci/arm64-xxhdpi-api30-en.json   # fail if MAX > 18 MB
   ```
   Also write `get-size total --dimensions=ABI,SCREEN_DENSITY` into the job summary to track trends ([bundletool](https://developer.android.com/tools/bundletool)).
3. **16 KB check:** zipalign `-P 16` plus `check_elf_alignment.sh` on the release APK, as in §6.2.
4. **Native library inventory:** list `lib/arm64-v8a/*.so` with sizes and fail on unexpected additions (e.g. `libfilamat*`, mediation SDKs).
5. **Dependency guard:** `./gradlew :app:dependencies --configuration releaseRuntimeClasspath > deps.txt`, diffed against a committed baseline (or the `dependency-guard` plugin). Fail on unreviewed changes.
6. **Merged-manifest permission check:** compare the permissions in `build/intermediates/merged_manifests/release/.../AndroidManifest.xml` against an allowlist (`CAMERA`, `INTERNET`, `ACCESS_NETWORK_STATE`, `AD_ID`).
7. **R8 metrics:** extract `r8.json` from the AAB and store it as an artifact.
8. **Scheduled perf job** (weekly + on `v*` tags): a GMD (AOSP, API 34, KVM) runs the Baseline Profile generator and a Macrobenchmark cold-start run. Store the median and P90. Alert when the median regresses by more than 10 % compared with the last run (absolute emulator numbers are noisy).
9. Keep `REQUIRE_RELEASE_CONFIG` gating from Gridlark (no test ads, no debug signing on tags).

**Toolchain alignment note:** the SceneView README (v4.65.0) lists **AGP 9.4.0+, Kotlin 2.4, JVM target 21, compileSdk 37, minSdk 24** ([SceneView README](https://github.com/sceneview/sceneview-android)). Gridlark is on Kotlin **2.3.21** with Java 17 bytecode. If SceneView is chosen, SpaceView needs **Kotlin 2.4.x** (plus a matching KSP and Compose compiler plugin) and `jvmTarget = 21`. JDK 21 on CI already supports this. Pass this to the rendering agent.

---

## 8. What "top-notch" looks like (benchmarks to hold ourselves to)
- **Download size:** best-in-class utility apps in India stay under ~15 MB, and "Lite" versions of major apps target ≤ 10 MB. A Filament + ARCore app at ≤ 15 MB would be excellent. 15–25 MB is acceptable. Above 30 MB measurably hurts conversion in India (§1.1).
- **Cold start:**
  - well-tuned Compose apps with Baseline and Startup profiles reach about 400–700 ms TTID on mid-range phones (**industry rule of thumb, not an official figure**);
  - Startup Profiles are worth a further 15–30 % (official);
  - Play's floor is 5 s cold, 2 s warm and 1.5 s hot.
  - **Our targets:** ≤ 800 ms mid-range, ≤ 1.5 s on 3 GB phones.
- **Stability:** top apps sit around 0.1–0.3 % crash rate and below 0.1–0.2 % ANR rate, versus Play's bad-behaviour lines of 1.09 % and 0.47 %. **Our targets:** crash < 0.3 % and ANR < 0.15 % overall, and no single phone model above 2 %.
- **Rendering:** a steady 30 fps in AR (matching the camera) is better than a variable 30–60. Google's ARCore guidance says users "prefer frame rates that are consistent and lower" ([ARCore performance](https://developers.google.com/ar/develop/performance)). Main-thread frames < 16 ms in the gallery (Macrobenchmark `FrameTimingMetric` P95 < 12 ms).
- **Memory:** P99 foreground well under the 2.0 GB 4 GB-tier limit (aim ≤ 700 MB). P99/P50 ratio < 3.5×; Google flags ≥ 3.5× as a likely leak ([memory usage vital](https://developer.android.com/google/play/vitals/memory-usage)).

---

## 9. Open questions for other agents / the developer
1. **Rendering agent:**
   - confirm the SceneView/Filament versions are 16 KB-aligned (run the CI check on a hello-world AAR);
   - confirm SceneView does not pull in `filamat-android`;
   - confirm it supports a KTX2 provider and meshopt;
   - check whether it ships R8 consumer rules.
2. **Product:** confirm **AR Required** at launch (§6.5) and the interstitial frequency cap (§5.3).
3. **Legal:** DPDP children's data and targeted ads (target audience setting) before **May 2027**.
4. **Play account type:** personal (needs the 12-tester, 14-day closed test) or organisation.
5. **Ads SDK:** Next-Gen vs legacy, decided by Next-Gen's official GA status at implementation time.

---

## 10. Sources
- Target API requirements: https://developer.android.com/google/play/requirements/target-sdk
- 16 KB page sizes (Play deadline 1 Feb 2027, checks, AGP/NDK): https://developer.android.com/guide/practices/page-sizes
- ARCore SDK releases (1.57.0): https://github.com/google-ar/arcore-android-sdk/releases
- Unity ARCore plugin changelog (ARCore 1.48 16 KB fix): https://docs.unity3d.com/Packages/com.unity.xr.arcore@6.2/changelog/CHANGELOG.html
- Filament releases (1.79.0, AAR sizes): https://github.com/google/filament/releases
- Filament README (glTF extensions, artifacts): https://github.com/google/filament
- SceneView Android (4.65.0, toolchain requirements): https://github.com/sceneview/sceneview-android and /releases
- flutter_sceneview changelog (Filament 16 KB alignment fix): https://pub.dev/packages/flutter_sceneview/changelog
- Enable ARCore, AR Required vs Optional: https://developers.google.com/ar/develop/java/enable-arcore
- ARCore performance: https://developers.google.com/ar/develop/performance
- ARCore CameraConfig.TargetFps: https://developers.google.com/ar/reference/java/com/google/ar/core/CameraConfig.TargetFps
- ARCore ML / CPU images: https://developers.google.com/ar/develop/java/machine-learning
- ADPF Thermal API: https://developer.android.com/games/optimize/adpf/thermal
- Android vitals overview and thresholds: https://developer.android.com/topic/performance/vitals
- Vitals launch time thresholds: https://developer.android.com/google/play/vitals/launch-time
- Vitals memory usage: https://developer.android.com/google/play/vitals/memory-usage
- Vitals DEX code optimisation: https://developer.android.com/google/play/vitals/code-optimization
- App startup time guide: https://developer.android.com/topic/performance/vitals/launch-time
- Enable app optimisation / R8 (AGP 9.x changes): https://developer.android.com/topic/performance/app-optimization/enable-app-optimization
- R8 keep rules troubleshooting: https://developer.android.com/blog/posts/configure-and-troubleshoot-r8-keep-rules
- Baseline Profile configuration (GMD): https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles
- Create Baseline Profiles: https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile
- Startup Profiles / DEX layout: https://developer.android.com/topic/performance/baselineprofiles/dex-layout-optimizations
- Compose strong skipping: https://developer.android.com/develop/ui/compose/performance/stability/strongskipping
- Play Feature Delivery: https://developer.android.com/guide/playcore/feature-delivery
- Play Asset Delivery: https://developer.android.com/guide/playcore/asset-delivery
- Play size limits: https://support.google.com/googleplay/android-developer/answer/9859372?hl=en
- bundletool: https://developer.android.com/tools/bundletool
- "Shrinking APKs, growing installs" (Google Play, India data): https://medium.com/googleplaydev/shrinking-apks-growing-installs-5d3fcba23ce2
- glTF Transform: https://gltf-transform.dev/
- AdMob optimise initialisation: https://developers.google.com/admob/android/optimize-initialization
- GMA Next-Gen quick start: https://developers.google.com/admob/android/next-gen/quick-start
- GMA Next-Gen ad preloading: https://developers.google.com/admob/android/next-gen/ad-preloading
- GMA Lite SDK deprecation: https://developers.google.com/admob/android/lite-sdk?hl=en
- Next-Gen migration (third party, unverified): https://dev.to/mr_manushukla/gma-next-gen-sdk-migration-3-deadlines-2-breaking-changes-and-1-mediation-trap-532e
- AdMob interstitial guidance: https://support.google.com/admob/answer/6066980?hl=en
- AdMob Play data disclosure: https://developers.google.com/admob/android/privacy/play-data-disclosure
- UMP / privacy: https://developers.google.com/admob/android/privacy
- DPDP Rules 2025 (PIB): https://static.pib.gov.in/WriteReadData/specificdocs/documents/2025/nov/doc20251117695301.pdf ; https://www.theweek.in/wire-updates/business/2025/11/14/del148-biz-dpdp-rules-ld-govt.html
- Photo & Video permissions policy: https://support.google.com/googleplay/android-developer/answer/14115180
- Android 16 orientation/resizability: https://android-developers.googleblog.com/2025/01/orientation-and-resizability-changes-in-android-16.html
- Play Integrity standard requests: https://developer.android.com/google/play/integrity/standard
- In-App Review: https://developer.android.com/guide/playcore/in-app-review
- Closed testing requirement: https://support.google.com/googleplay/android-developer/answer/14151465?hl=en-GB
- Android emulator on GitHub Actions (KVM): https://github.com/ReactiveCircus/android-emulator-runner
- Google Play Services for AR APK size (APKMirror, unverified): https://www.apkmirror.com/apk/google-inc/arcore/arcore-1-47-2431602-release
- Sentry size anecdote (PayU, dated): https://payu.in/blog/?p=10602
- LeakCanary artifacts: https://central.sonatype.com/artifact/com.squareup.leakcanary/leakcanary-android-release/
