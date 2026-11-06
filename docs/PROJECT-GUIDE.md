# SpaceView: Project guide (plain English)

This guide explains every file in the SpaceView folder for a reader who does not write code. It describes the code as it is today, after milestones M0 to M3. Anything not built yet is marked **coming**.

Technical words are explained in plain words the first time they appear. Every one of them is also in the **Glossary** (section 5).

**How to read it:**
- Section 1 is the one-page overview.
- Sections 2 and 3 are lookup tables to use when you meet a file name.
- Section 4 explains how the work is checked, and what to do when a check fails.
- Section 6 tells you where to look when you want to change something.

---

## 1. The big picture

### What the app does today
SpaceView is an Android app that turns a phone camera into a measuring tool.
- **Measure:** you point the phone at things and tap a big "+" button to drop points. The app shows a **distance**, a **height**, or a **rectangle** (width × height and area).
- **An honest error margin:** every number comes with its own "±" (plus or minus), which says how far off it could be. It also gets a **Good / Fair / Rough** badge.
- **Calibrate:** you can make the app more accurate by measuring a span of 1 to 5 m that you have already checked with a tape.
- **History:** this keeps your last 200 saved measurements.
- **Scan an object:** you can scan a frame, a TV, an appliance or a box. The app measures it, takes a straight-on photo of its front, and saves it to the **Gallery** as a true-size 3D box with that photo on its front. You can rename it, edit its sizes, share it or delete it.
- **Ads:** there are a few ads, with strict limits. They never appear on camera screens.
- **Languages:** the app is in English and Hindi.

**Coming**, per `docs/PLAN.md` and the proposed `docs/ARCHITECTURE-v2.md`:
- "Place in my room" (M4). Today that button only shows "coming soon".
- A paid Pro tier with Play Billing.
- Smart Measure.
- Furniture templates.
- A rough on-device 3D scan.

### The four rooms of the code
The code is split into four **modules**. A module is a self-contained part of the project with its own folder and its own rules about what it may use. Think of the app as a house with four rooms:

| Room (module) | Analogy | What lives there |
|---|---|---|
| `engine` | **The maths brain** | Pure calculation: distances, surfaces, averaging, the ± error margin, units (cm, inches, feet), the "does it fit?" verdict, and writing 3D box files. It knows nothing about phones, cameras or screens, which is why it can be tested quickly on any computer. |
| `ar` | **The eyes** (camera and 3D world) | It talks to Google's **ARCore**, which tracks the phone's position in the room. It finds the surface under the crosshair, ignores shaky or dark frames, and takes the front photo of a scanned object. It hands clean numbers to the maths brain. It has no buttons or screens. |
| `data` | **The filing cabinet** | It saves and loads things: the History list, Gallery items (3D model, photo and thumbnail files), and measuring settings such as calibration. |
| `app` | **The screens and buttons** | Everything you see and touch: Home, Measure, Scan, Gallery, History, Settings and onboarding. It also holds the ads, the colours and the English and Hindi text. It wires the other three rooms together. |

The rule for doors between the rooms:
- `app` may use all three other rooms.
- `ar` and `data` may use `engine`.
- `engine` uses nothing.

Because of this rule, the most important code (the accuracy maths) has no outside parts that could break it.

### How a measurement travels from camera to screen to saved
1. **You choose "Distance" on Home.** Before the camera opens, the app checks two things (`ArGate`): that you allowed camera access, and that "Google Play Services for AR" is installed.
2. **The camera starts.** A library called **SceneView** shows the live camera picture. Underneath it, **ARCore** combines the video with the phone's motion sensors to build a 3D map of the room, about 30 times a second.
3. **Every frame, the eyes look under the crosshair.** `MeasureSession` (in `ar`) asks ARCore what surface is at the centre of the screen. It might be a detected floor or table (a **plane**), a depth reading, or a cluster of 3D dots. For plain walls, where ARCore finds nothing, the app fits its own flat surface to nearby dots.
4. **Bad frames are thrown away.** `TrackingGate` rejects frames where the phone moves too fast, the room is too dark, or tracking was lost. `SampleStabilizer` collects 15 to 30 good readings of the same spot. The maths brain (`PointEstimator`) then averages them and drops any odd ones.
5. **You press "+".** The point is pinned to the real world with an **anchor**. When you place the second point, `MeasurementComposer` asks the maths brain (`Uncertainty`) for the length, its ± and its badge.
6. **The screen updates calmly.** At most 10 times a second, new results flow to the screen through `MeasureViewModel` and appear in `MeasureHud` and `ResultCards`. The formatter (`Units`) never shows more digits than the ± justifies. For example, it will not show "45.53 cm" if the error is ±1 cm.
7. **You tap Save.** The measurement goes to `MeasurementRepository` (in `data`), which writes it into the on-phone database file `spaceview.db`. The ad counter adds one.
8. **You leave the camera.** Only if you saved something, the next non-camera screen *may* show one full-screen ad, and only if the ad rules in `AdPolicy` allow it.

A **scan** follows the same path, with three extra stops:
- After the outline, the app waits until you hold the phone straight and still, then takes the front photo by itself.
- It straightens that photo into a flat picture.
- On Save, `GalleryRepository` writes three files (the 3D box, the photo and a small preview) plus a database row.

---

## 2. Folder map

Everything below is in `C:\Users\Kanishk\Desktop\Dev\SpaceView`. **Tracked** means the file is stored in git, the project's history system, and on GitHub.

### Tracked folders and files

| Item | What it is, in plain words |
|---|---|
| `.github/workflows/android.yml` | The instructions for **CI**: the robot on GitHub that rebuilds and checks the app every time code is pushed. Section 4 explains each step. |
| `ci/` | Helper files the CI robot uses: the list of allowed permissions, two Python check scripts (16 KB check and dependency guard), the list of approved libraries, and the description of a "typical arm64 phone" used to measure download size. |
| `gradle/` | `libs.versions.toml` is the **shopping list**: every outside library and its exact version, in one place. `wrapper/` holds a tiny program that downloads the exact build tool version (Gradle 9.6.1) so every computer builds the same way. |
| `gradlew`, `gradlew.bat` | Start buttons for the build tool: `gradlew` for Linux and Mac (used by CI), `gradlew.bat` for Windows. On this PC they can't download Gradle, so local builds use `~/.android-toolchain/g.sh` instead. |
| `settings.gradle.kts` | Names the project and lists the four modules (`engine`, `ar`, `data`, `app`), plus where libraries are downloaded from (Google and Maven Central). |
| `build.gradle.kts` (top level) | Declares which build plugins exist, without switching them on. Each module's own `build.gradle.kts` switches on what it needs. |
| `gradle.properties` | Build-tool settings. The most important one keeps the build's memory small (about 1.5 GB) because this PC has only about 2 GB free. CI uses a bigger allowance. |
| `.gitignore` | The list of things git must **not** store, such as build output, `local.properties`, signing keys, `builds/` and the lock files. |
| `.gitattributes` | Tells git how to treat line endings, and which files are binary (images, `.jar`, `.glb`) so they are never altered. |
| `docs/` | All written documents: the plan, the test checklist, the research, the reviews, this guide, and `build_html.py` (the script that makes `SpaceView.html`). See the table below. |
| `HANDOFF.md` | The living status diary: what is done, what is next, the decisions and the rules. Any new session reads this first. |
| `SpaceView.html` | The "Build Book": every `.md` document bundled into one web page you can open in a browser. It is made by `python docs/build_html.py`. Never edit it by hand. |
| `engine/`, `ar/`, `data/`, `app/` | The four modules (rooms). Section 3 explains every file inside them. |

### Inside `docs/`

| File | What it is |
|---|---|
| `PROJECT-GUIDE.md` | This guide. |
| `PLAN.md` | The approved plan: scope, user journey, modules, budgets, milestones M0 to M6, and the release quality bar. |
| `TESTING.md` | The checklist of things only a real phone can check. AR does not run on an emulator. |
| `ARCHITECTURE-v2.md` | **Proposed** design for M4, Pro, Smart Measure, templates and the 3D scan. It is not committed yet and is awaiting your decisions. |
| `research/*.md` | Background studies: measurement accuracy, UI and UX, business, Android performance, plus two new ones (`pro-monetization.md`, `3d-capture.md`) that are not committed yet. |
| `reviews/*.md` | Review reports (problems found, graded BLOCKER, MAJOR or MINOR) and gate verdicts for each milestone. |
| `build_html.py` | Turns all the `.md` files into `SpaceView.html`. |

### Local-only items (not in git, safe to ignore)

| Item | What it is |
|---|---|
| `local.properties` | One line telling the build where the Android SDK sits on *this* PC. It is created automatically and never shared. |
| `build/`, and `build/` inside each module | Auto-generated build output: compiled code, reports and APKs. It can be deleted at any time; the next build recreates it. |
| `.gradle/` | The build tool's private cache on this PC. Auto-generated. |
| `.kotlin/` | The Kotlin compiler's scratch files. Auto-generated. |
| `builds/` | Hand-picked installable files for you: `SpaceView-M2-final-debug.apk`, `SpaceView-M2-final-release.apk`, `SpaceView-M3-debug.apk` and `SpaceView-M3-release.apk`. They are kept out of git because they are large. |
| `.m0-ready` | An empty marker file from the first day. It said "the empty app builds", so the second builder could start. It is no longer used. |
| `.build.lock` | A temporary folder that means "someone is building right now, wait" (see section 4.6). It exists only during a build. |

---

## 3. Every file, module by module

Inside each module, the code sits in `src/main/kotlin/com/spaceview/<module>/`. The long path is just Android's naming habit. The tests sit in `src/test/...`. Every code file ends in `.kt`, meaning it is written in **Kotlin**, the main language for Android apps.

> **What is a test?** A test is a small extra program that runs a piece of the real code with known inputs and checks the answer. For example: "two points exactly 1 m apart must give 1 m". Tests run automatically, in seconds, without a phone. If someone later breaks the maths, a test fails and the change is stopped. Each test has a descriptive name, such as `rejectsOutliers` or `neverOnCameraScreens`, so a failure tells you *what* went wrong. Today there are **391 tests**: engine 163, ar 83, data 48 and app 97, plus 2 that only run on a real device.

### 3.1 `engine`: the maths brain

`engine/build.gradle.kts` sets this module up as plain Kotlin with no Android parts. It also switches on **Kover**, which measures how much of the code the tests exercise and fails the build if that drops below 90%. Today it is about 99%.

| File | What it does, in plain words | Why it matters to you |
|---|---|---|
| `Vec3.kt` | The basic building blocks of 3D maths: a point or arrow in space (`Vec3`), a 2D point, a rotation, a **pose** (position plus facing direction), and a "fuzzy point" that carries its own error cloud. | Everything else is built on these. |
| `Plane.kt` | Flat surfaces. It can tell how far a point is from a surface, and where a line of sight hits a wall. It also contains **RANSAC**, a method that fits a flat surface to messy 3D dots while ignoring stray ones. | This is how plain walls (where ARCore sees nothing) still get measured. |
| `PointEstimator.kt` | It takes 15 to 30 shaky readings of one spot, throws out odd ones, and returns the best single point plus how sure it is. It refuses readings from frames where tracking was poor. | Holding still for a moment makes points steadier and more accurate. |
| `Uncertainty.kt` | The heart of the "honest ±". It combines hand shake, aiming error, the phone's built-in scale error and a minimum floor, and turns them into the **95%** "±" you see. It also decides the **Good / Fair / Rough** badge and defines the **device tiers** (phones with depth, without depth, or with a depth sensor). | Accuracy is the app's main promise. The badge thresholds and per-phone error levels live here. |
| `Calibration.kt` | Works out a correction factor from a length whose true size you know. A factor of 1.02 means "AR reads 2% short, so stretch by 2%". It refuses silly factors (outside 0.9 to 1.1) and noisy attempts, and it applies only on the same surface and at a similar distance. | It explains why calibration is sometimes refused. That is deliberate honesty, not a bug. |
| `Shapes.kt` | Turns 3 or 4 corner points into a rectangle (width, height, area, diagonal), and 4 points into a box (width, depth, height). It checks that the corners really form a square-cornered shape. | It drives the Rectangle mode and object scans. |
| `Units.kt` | Converts metres into mm, cm, m, decimal inches, fraction inches (23 5/8 in) or feet-and-inches (5′ 8″). It picks sensible units for the context (inches for frames, ft-in for rooms) and never shows more digits than the ± allows. | It controls how every number on screen looks. |
| `FitVerdict.kt` | The "does it fit?" verdict: **Fits / Tight / Too big**, taking the ± of both the object and the space into account. | Built and tested, but used by **Place in room (coming, M4)**. |
| `Homography.kt` | The maths that straightens a photo taken at an angle so it looks straight-on, like a scanner app does. | It makes the Gallery's front photos look flat and correct. |
| `GlbWriter.kt` | Writes a true-size 3D box file (**GLB**, where 1 unit = 1 metre) with the front photo on its front face and a plain colour on the other sides. | Every Gallery item's 3D model is made here. |

**Engine tests** (`engine/src/test/...`):

| Test file | What it checks, in plain words |
|---|---|
| `GeometryTest` | Basic 3D arithmetic, rotations and positions are correct. |
| `PlaneFitTest` | Surface fitting works on clean dots, and still works when 30 to 45% of the dots are junk. |
| `PointEstimatorTest` | Averaging reduces error, odd readings are thrown out, and bad-tracking frames are refused. |
| `UncertaintyTest` | The ± and the badge thresholds behave as designed for each phone tier. |
| `CoverageTest` | The big honesty test. Under tricky, realistic noise, the true value really falls inside the shown ± at least 93 to 95% of the time. |
| `CalibrationTest` | A deliberate 3% error is corrected, and absurd or noisy calibrations are refused. |
| `ShapesTest` | Rectangles and boxes come out right, skewed shapes are flagged, and noisy corners stay honest. |
| `UnitsTest` | Unit conversions round-trip exactly. Fractions, feet-inches and precision rules are right. |
| `FitVerdictTest` | Fits, Tight and Too big are decided correctly, including on the borderline. |
| `HomographyTest` | Photo straightening maps corners exactly and rejects impossible shapes. |
| `GlbWriterTest` | The 3D file is valid, has the right size and origin, and handles the photo correctly. |
| `ReviewFindingsTest`, `M2ReviewEngineTest` | Each problem found by the M0–M1 and M2 reviews stays fixed. These are "regression tests". |
| `MiniJson.kt` | Not a test. It is a tiny reader the GLB tests use to look inside the 3D file. |

### 3.2 `ar`: the eyes

`ar/build.gradle.kts` makes this an Android library that uses ARCore 1.57 and SceneView 4.65, and passes them on to `app`. `ar/consumer-rules.pro` is an empty-on-purpose note for the code shrinker (R8, see the glossary).

| File | What it does, in plain words | Why it matters to you |
|---|---|---|
| `MeasureSession.kt` | The **conductor** of every AR screen. It receives each camera frame, finds the point under the crosshair, adds points when you press "+", handles undo, clear, freeze-frame and the scan's front photo, and sends a calm summary to the screen at most 10 times a second. Heavy maths runs on a separate background worker so the screen never stutters. | This is the largest and most central AR file. Most AR behaviour starts here. |
| `MeasurementComposer.kt` | Turns placed points into a result: distance, height, rectangle or box, with ± and badge. It applies calibration only where it is valid, and chooses one improvement tip ("move closer", "add light"…). It also defines the measuring modes. | It decides what result card you see. |
| `ScanComposer.kt` | For scans, it decides which face of the object is the "front" (the one you are standing at), and which way is up, so the photo is never upside down or mirrored. | It makes sure the correct face is photographed. |
| `FrameReaders.kt` | It pulls raw facts out of each ARCore frame (the surface hit, 3D dots, depth, camera image), then closes ARCore's buffers immediately. | It keeps memory use low and frames fast. |
| `TrackingGate.kt` | Judges whether tracking is good, too fast, too dark, low texture or lost. It turns that into friendly messages like "Move more slowly", with no jargon. | It explains the coaching messages you see. |
| `SampleStabilizer.kt` | Decides whether you are *holding still* (keep averaging) or have *moved the crosshair* (start over), and passes readings to the engine's averager. | It explains why the ring fills up only when you hold steady. |
| `LocalSurfaceModel.kt` | Fits the app's own flat surface to 3D dots around the crosshair, for walls and TV screens that ARCore doesn't recognise. | It makes plain walls measurable. |
| `SurfaceChooser.kt` | Chooses between ARCore's own hit and the app's fitted surface. It never lets the fit jump to a different layer, such as the floor behind a box. | It prevents a box-top point from landing on the floor. |
| `DepthPatch.kt` | Holds a small window of depth readings (how far each pixel is) on phones that support the Depth API. | Depth gives steadier points. It can be switched on in Settings. |
| `AimModel.kt` | Estimates how accurately a person can aim the crosshair, measured in camera pixels. This becomes part of the ±. | Its constants (2 pixels) are tunable after the bench test. |
| `ViewProjection.kt` | Converts between 3D world positions and screen pixels, both ways. | It draws the yellow measuring lines in the right place. |
| `ImageProjection.kt` | The same idea for the camera's photo, so the object's corners can be located in the picture. | It is needed to straighten the front photo. |
| `FrontCaptureGate.kt` | The "take the photo now?" rules: the object fully in view, the phone within 15° of straight-on (25° for boxes), held still for 0.6 s. One hint at a time: "Tilt", "Step back", "Hold still"… | It controls the automatic front photo. |
| `FrontCaptureController.kt` | Runs the front-photo steps over time: arm, wait, capture, cancel. It skips repeated frames, and refuses to shoot if the points lost their anchors. | It fixed the M3 blocker where the photo never fired. |
| `Nv21.kt` | Converts the camera's raw picture format into normal colours. | It is the plumbing for the front photo. |
| `SessionSetup.kt` | Picks the camera mode (back camera, 30 frames a second), detects what this phone supports (depth, depth sensor) and sets the device tier. | Your phone's tier sets its ± level. |
| `ArCoreSupport.kt` | Checks whether "Google Play Services for AR" is installed, and starts the install if not. | It drives the "install AR services" screen. |
| `ArModule.kt` | A short marker that records "this app is AR Required". | It is a design decision written in code. |

**AR tests** (`ar/src/test/...`):

| Test file | What it checks |
|---|---|
| `MeasurementComposerTest` | Each mode needs the right number of points, height is truly vertical, calibration is applied and flagged, and tips make sense. |
| `DisplayedBoundTest` | The ± you *read* on screen is never smaller than the true ±, even after rounding, across every mode, unit and phone tier. |
| `M2ReviewArTest` | The M2 review's AR fixes stay fixed. For example, a bank card at 30 cm is never shown better than about ±1 cm, and a box top never jumps to the floor. |
| `ScanComposerTest` | Box width, depth and height are right, and the front is the side you stand at. Standing at the side swaps width and depth. |
| `FrontCaptureGateTest`, `FrontCaptureControllerTest` | The photo fires only when steady and straight, works on 60 Hz and 120 Hz screens, and refuses un-anchored points. |
| `ImageProjectionTest`, `ViewProjectionTest` | 3D-to-picture and 3D-to-screen conversions are correct, and points behind the camera are refused. |
| `StabilizerAndSurfaceTest` | Holding still locks onto the true point, moving restarts, and wall fitting works. |
| `TrackingGateTest` | Warm-up, too-fast, lost and low-light states are reported correctly. |
| `Nv21Test` | Colour conversion is correct, including odd image sizes. |

### 3.3 `data`: the filing cabinet

`data/build.gradle.kts` sets up this Android library with **Room** (the database tool) and **DataStore** (the small-settings tool). It also tells Room to save a copy of the database's layout into `data/schemas/`. `data/consumer-rules.pro` is a near-empty note for the code shrinker.

Everything is stored **privately on the phone**: no cloud, no account, and no storage permission is needed.

| File | What it does, in plain words | Why it matters to you |
|---|---|---|
| `SpaceViewDatabase.kt` | Defines the database file `spaceview.db` and its two tables: `measurements` (History) and `gallery_items` (Gallery). It also holds the **migration** from version 1 to version 2 (see below). | Your users' saved work lives here. Changing it carelessly can lose their data. |
| `StorageLayout.kt` | Decides where files go: `gallery/<item id>/` inside the app's private folder. | It keeps every item's files together and safe. |
| `history/MeasurementEntity.kt` | The shape of one History row: the kind, the value in metres, its error, the badge, the time, the label, plus the phone tier, ARCore version and calibration factor. | It defines what is remembered about each measurement. |
| `history/MeasurementDao.kt` | The list of database questions for History: add, list newest first, rename, delete, and trim to the newest N. A **DAO** is a menu of allowed database actions. | — |
| `history/MeasurementRepository.kt` | The friendly front desk for History. It saves, lists, renames and deletes, keeps only the newest **200**, and reports storage failures without crashing. | The History limit is set here. |
| `gallery/ScanDraft.kt` | Describes a finished scan before it is saved: the object kind (frame, TV, appliance, box, other), and each dimension with where it came from (**measured** with ±, **preset** guess, or **typed** by the user). | It is why typed values show "~" and no ±. |
| `gallery/GalleryItemEntity.kt` | The shape of one Gallery row: sizes and their ±, the source of each size, the original measured values (so "reset to measured" stays possible), and the file names. | — |
| `gallery/GalleryDao.kt` | The database menu for the Gallery: add, list, rename, update sizes, update files, delete. | — |
| `gallery/GalleryRepository.kt` | The Gallery's front desk. On save it shrinks the photo (front at most 1024 px, preview 256 px), builds the 3D GLB box, writes the files, and adds the row. It keeps each item **under 0.5 MB**. It also handles edits (a new 3D file each time, and the old one is deleted only after success), rename, delete, share copies and tidying up leftover files. | Gallery size, quality and safety rules live here. |
| `gallery/GalleryImageCodec.kt` | Shrinks images and saves them as JPEG (photo) or WebP (preview) on Android. Tests swap in a desktop version. | — |
| `settings/MeasureSettingsRepository.kt` | Small measuring settings in their own DataStore file (`spaceview_measure`): the saved calibration and its scope, whether depth is on, and whether the safety notice was shown. A calibration counts only in the AR session that made it, and is "stale" after 30 days. | It explains why an old calibration is shown but not applied. |

#### The `data/schemas/*.json` files: database versions and why "upgrades" matter
A database has a fixed layout: which tables exist and which columns each has. Every time the layout changes, its **version number** goes up, and Room writes a snapshot of the new layout:
- `1.json` is **version 1** (M2), with only the `measurements` table.
- `2.json` is **version 2** (M3), which adds the `gallery_items` table and four new History columns (phone tier, ARCore version, calibration factor and raw length).

When someone with the M2 app installs the M3 update, their phone still holds a version-1 database full of their History. The **migration** (`MIGRATION_1_2` in `SpaceViewDatabase.kt`) is a small script that upgrades the old file in place: it adds the new table and columns and keeps every old row.

**Why it matters to you:** without a correct migration, an app update would either crash on start, or wipe the user's saved History. Both lead to one-star reviews. So:
- Every future layout change needs a version bump, a new migration, a new schema `.json`, and a migration test.
- The migration test (`SpaceViewMigrationTest`) can only run on a device, and it **has never run on a real phone yet**. That is why the first item of `docs/TESTING.md` §16 is "install M3 over M2 and check your History survived".

**Data tests:**

| Test file | What it checks |
|---|---|
| `HistoryRepositoryTest` | Save and read back, newest first, trim to the limit, damaged rows skipped, and storage failures reported rather than crashing. |
| `MeasureSettingsRepositoryTest` | Settings defaults, saving calibration, refusing implausible calibrations, the depth flag, and "clear calibration" removing everything. |
| `gallery/GalleryRepositoryTest` (34 tests) | The 3D model, photo, preview and database row are all written, photos are capped at 1024 and 256 px, typical and worst-case scans stay under 0.5 MB, a broken photo isn't fatal, and edit, rename, delete and clean-up are safe. |
| `gallery/JvmImageCodec.kt` | Not a test. It is the desktop image tool the tests use instead of Android's. |
| `androidTest/SpaceViewMigrationTest` | **Device-only.** Upgrading a version-1 database to version 2 keeps every row. It is built on CI but needs a phone or emulator to run. |

### 3.4 `app`: the screens and buttons

#### Build and setup files
| File | What it does, in plain words | Why it matters to you |
|---|---|---|
| `app/build.gradle.kts` | The app's recipe. It sets the app id `com.spaceview.app` (debug builds add `.debug`, so both can be installed side by side). It sets the minimum Android version (7.0) and the target (Android 16). It keeps only English and Hindi, and only ARM phones. It turns on shrinking for release builds. Ads use Google's **test ads** unless the real AdMob IDs are supplied. Signing uses a real key from secrets, or else the debug key. It also refuses a store build (a version tag) that lacks real ads, a real signing key, a support e-mail or a privacy-policy link. It strips the 12 MB `filamat` tool. | Version numbers, ad IDs and signing are controlled here. Pro billing is **coming**: the library is in the shopping list but not used yet. |
| `app/proguard-rules.pro` | Notes for **R8**, the release shrinker. It is mostly empty on purpose, plus an M6 to-do to tighten SceneView's over-broad rules and save size. | It affects release size. |
| `src/main/AndroidManifest.xml` | The app's **ID card** for Android. It declares the permissions: camera, plus internet and network state for ads (vibration, foreground service and wake-lock are actively removed). It declares "AR Required", so Play lists the app only on ARCore phones. It also holds the AdMob app ID, the FileProvider for sharing, and the single main screen. | Every permission a user sees comes from here. CI checks it against `ci/allowed-permissions.txt`. |

#### App shell (`com/spaceview/app/`)
| File | What it does | Why it matters to you |
|---|---|---|
| `SpaceViewApplication.kt` | The app's starting object. It does almost nothing on purpose, so the app opens fast (target under 0.8 s). | Start-up speed. |
| `AppContainer.kt` | A **toolbox** that hands out the History, Gallery and settings stores. Each is created only when first needed. | Start-up speed. |
| `MainActivity.kt` | The one and only window. It draws the screens, then starts the ad system *after* the first frame is drawn. | — |
| `nav/AppNavHost.kt` | The **map of screens** and the routes between them: Home, Onboarding, Measure, Calibrate, History, Settings, Scan, Gallery and Gallery item. It also decides when leaving a screen may trigger an ad. "Place in my room" shows "coming soon" for now. | Adding or re-ordering screens happens here. |

#### Screens
| File | What it does | Why it matters to you |
|---|---|---|
| `ui/home/HomeScreen.kt` | The Home screen: Distance, Height, Rectangle, Calibrate, History, Scan an object, Gallery and Settings. The camera starts only after you pick something. | The first thing users see after onboarding. |
| `onboarding/OnboardingScreen.kt` | Three skippable welcome pages (what it does, how to measure well, honest accuracy of "typically ±2%"), ending with "Calibrate now / Later". | First impressions and honesty. |
| `measure/MeasureScreen.kt` | The Measure route: the permission and AR checks, then the live camera view, keeping the screen awake, and short messages ("Saved"). | — |
| `measure/ArGate.kt` | Asks for camera permission with a reason, offers "Open settings" if it was blocked, and checks or installs AR services. | The first-time camera experience. |
| `measure/MeasureViewModel.kt` | The **brain behind the Measure screen**. It holds the settings, runs calibration (known length 1 to 5 m), and saves results to History with the canonical record. | The calibration rules for users are here. |
| `measure/MeasureHud.kt` | Everything drawn over the camera: the crosshair with its stability ring, the live reading, the mode tabs, the "+", undo and freeze buttons, and coaching messages. | The look of the AR screen. |
| `measure/ResultCards.kt` | The result card (value, ±, badge, tip, Save), the calibration cards and the known-length entry card. | — |
| `measure/FreezeOverlay.kt` | **Freeze-frame**: holds the picture still so you can drag the last point precisely, with a 2× magnifier. | A precision feature. |
| `measure/DimensionPainter.kt` | Draws the yellow drafting-style measuring lines on the camera view. | — |
| `measure/MeasureFormatter.kt` | Chooses units by context (rooms in ft-in, small objects in mm) and formats numbers honestly. | Unit thresholds (1.5 m, 15 cm) are here. |
| `measure/ClickSounds.kt` | Soft click sounds made in code (no sound files). It uses sound instead of vibration, because vibration disturbs AR tracking. | — |
| `history/HistoryScreen.kt` | The History list, with delete, plus a banner ad fixed at the bottom. | — |
| `scan/ScanRoute.kt` | The whole scan journey: pick a type → outline → photo → review → saved (Place / Share / Done). It never shows ads while scanning. | — |
| `scan/ScanArScreen.kt` | The AR part of a scan: outlining the object, then guiding you to the automatic front photo. | — |
| `scan/ScanReviewScreen.kt` | The review page: width, height and depth with ±, the diagonal for frames and TVs, editable values, a name, and **Save to Gallery**. What you see is exactly what is saved. | — |
| `scan/ScanViewModel.kt` | The brain behind the scan screens. It saves exactly once, survives the phone killing the app in the background, and retries failed saves. | Reliability. |
| `scan/ScanDraftBuilder.kt` | Turns the AR outline into the reviewed sizes. It fills preset depths (frame 3 cm, TV 8 cm), marks edited values as estimates, and computes the diagonal. | The preset depths are here. |
| `scan/imaging/FrontPhotoRectifier.kt` | Straightens the front photo to the measured width-to-height shape. | Photo quality. |
| `scan/imaging/RectifyMath.kt` | The pixel maths behind the straightening, kept separate so it can be tested. | — |
| `scan/imaging/JpegEncoder.kt` | Saves photos as JPEG at set qualities (90 for the scan, 82 for the gallery). | The size vs quality trade-off. |
| `gallery/GalleryScreen.kt` | A grid of saved items with sizes and ±, plus a banner ad. | — |
| `gallery/GalleryItemScreen.kt` | One item's page: photo, size sheet, badge, Place (coming), Share, Edit sizes, Rename and Delete. | — |
| `gallery/GalleryViewModels.kt` | The brains behind the two Gallery screens. They prepare text off the main thread and report errors. | — |
| `gallery/GalleryFormatter.kt` | How Gallery sizes read: inches for frames, "~" for estimates, and the TV diagonal ("Outer diagonal (incl. bezel)"). | — |
| `gallery/GalleryImages.kt` | Loads and caches photos, and shares them to WhatsApp and other apps through the FileProvider. | — |
| `settings/SettingsScreen.kt` | Settings: units (Auto, Metric, Imperial), sounds, depth, recalibrate or clear calibration, ad privacy options, privacy policy, "Remove ads" (**coming soon**), version and licences. | — |
| `settings/SettingsRepository.kt` | Stores user settings and "onboarding done" in DataStore (`spaceview_settings`). | — |

#### Shared building blocks (`common/`)
| File | What it does |
|---|---|
| `AccuracyBadge.kt` | The Good / Fair / Rough badge: colour, label and spoken text. |
| `AddPointButton.kt` | The big yellow "+" button on AR screens. |
| `CoachingCard.kt` | The dark hint pill ("Move your phone slowly…"), with optional action and close buttons. |
| `DimensionLabel.kt` | The "45.5 cm ± 0.5" pill and dimension lines. |
| `InstrumentCard.kt` | The flat bordered card used on every non-camera screen. |
| `SpokenLength.kt` | Makes TalkBack (the screen reader for blind users) say "45.5 centimetres, plus or minus 0.5 centimetres". |

#### Ads (`ads/`)
| File | What it does | Why it matters to you |
|---|---|---|
| `AdPolicy.kt` | **The ad rules.** No ad during the first 3 saves; at most 1 per 3 saves; at least 3 minutes apart; at most 4 a day; never on camera screens; never before "Place" or "Share". | Change ad frequency here (`AdRules`). It affects revenue and reviews. |
| `MeasureVisitTracker.kt` | Decides *when* an ad may even be considered: only after a visit where something was saved, and only once you are on a normal screen. The chance expires after 5 s. | It prevents ads popping up at odd moments. |
| `AdsManager.kt` | Runs the ad system. It asks for consent first (**UMP**), starts AdMob in the background, preloads one full-screen ad, and shows it only when the rules allow. | — |
| `BannerAd.kt` | The small banner ad with an "Ad" label, on non-camera screens only (History, Gallery, Settings). | — |
| `AdScreenEffects.kt` | Marks camera screens as "no ads here", and lets normal screens use a pending ad chance. | — |
| `DataStoreAdCounterStore.kt` | Remembers the ad counters (saves, last ad time, ads today) in a separate small file. | — |

#### Look and feel (`ui/theme/`)
| File | What it does |
|---|---|
| `Color.kt` | The "precision instrument" palette: graphite and paper greys, one signal yellow, and muted status colours. Light and dark versions. |
| `Theme.kt` | Puts the colours, shapes (small rounded corners) and text styles together into the app theme. |
| `Type.kt` | Text sizes and weights. It uses the phone's own font (no font file, to save size) with fixed-width digits so live numbers don't jiggle. |

#### Resource files (`app/src/main/res/`)
**Resources** are the non-code parts of the app: text, colours, icons and settings files.

| File | What it is |
|---|---|
| `values/strings.xml` | **All English text** in the app, about 295 lines, each with a name such as `home_distance` = "Distance". |
| `values-hi/strings.xml` | The **Hindi** version of every one of those lines (all except the app name, which stays "SpaceView"). If a Hindi line is missing, lint fails the build. |
| `values/colors.xml`, `values-night/colors.xml` | The background colour shown for a split second before the app draws, in light and dark mode, plus the icon colours. |
| `values/themes.xml`, `values-night/themes.xml` | The basic Android window theme for light and dark mode. The real styling is in `ui/theme/`. |
| `drawable/ic_launcher_foreground.xml`, `drawable/ic_launcher_monochrome.xml` | The app icon drawing (a crosshair in corner brackets), in colour and in single-colour form for themed icons. |
| `mipmap-anydpi-v26/ic_launcher.xml` | The modern "adaptive" icon (Android 8+): background, foreground and monochrome layers. |
| `mipmap-anydpi/ic_launcher.xml` | The older-style icon for Android 7. |
| `xml/backup_rules.xml`, `xml/data_extraction_rules.xml` | What Google backup may copy: settings, yes; calibration, never (it belongs to one phone). Gallery and History are not backed up today. |
| `xml/file_paths.xml` | Which folder the FileProvider may share: only the temporary "share" copies, nothing else. |

#### App tests (`app/src/test/...`)
| Test file | What it checks |
|---|---|
| `ads/AdPolicyTest` | Every ad rule: first ad on the 4th save, 1 per 3 saves, 3 minutes apart, 4 a day, never on camera, never before Place or Share. |
| `ads/MeasureVisitTrackerTest` | Leaving without saving never triggers an ad; Place never triggers one; one save gives exactly one chance. |
| `ads/DataStoreAdCounterStoreTest` | Ad counters and user settings survive restarts. |
| `measure/MeasureLogicTest` | Units by context, never-too-precise formatting, and calibration accepted or refused correctly, valid only in its own session. |
| `measure/KnownLengthCalibrationTest` | Known-length calibration fixes a 3% error, judges a 1 m span honestly, refuses ends on two surfaces, and keeps card and A4 hidden in v1. |
| `scan/ScanDraftBuilderTest` | Object kinds map to shapes and preset depths, edited values become estimates, and the ± rounds up. |
| `scan/ScanViewModelTest` | Double-tap Save stores once, a failed save can be retried, and the flow survives the app being killed. |
| `scan/FrontPhotoOrientationTest` | The straightened photo is upright and not mirrored, whether you hold the phone upright, sideways or at an angle. |
| `scan/imaging/RectifyMathTest` | Straightening maths: no mirroring, true perspective corrected, size limits respected. |
| `gallery/GalleryFormatterTest` | Gallery text: inches for frames, "~" for estimates, the TV diagonal and its ±. |

---

## 4. How the pieces are checked

Think of it as a series of safety nets. Each one catches a different kind of mistake.

### 4.1 Unit tests
- **What they are:** the 391 automatic checks described in section 3. They run on a computer without a phone, in a few minutes.
- **How they run:** on this PC through `~/.android-toolchain/g.sh` (a small script that runs Gradle with the local toolchain), and on GitHub on every push.
- **Coverage:** **Kover** measures what share of the engine's lines the tests actually run. The build fails below 90%; today it is about 99%.
- **If they fail:** the report names the failing test, such as `atMostFourPerDay_resetsNextDay`, and that name says what broke. You don't fix code yourself. Pass the test name to the orchestrator; a builder fixes the code (or, rarely, the test, if the rule really changed). Nothing is committed while a test is red.

### 4.2 Lint
- **What it is:** an automatic proof-reader for Android code. It flags missing Hindi translations, wrong permissions, accessibility problems, outdated patterns and likely crashes.
- **When it runs:** on all three Android modules, on debug and release builds. Any finding stops the build (`abortOnError = true`). A few checks are switched off on purpose, each with a written reason in `app/build.gradle.kts`.
- **If it fails:** the lint report (inside the CI **reports** download) names the file and the line. The most common cause is a new English text line without its Hindi twin.

### 4.3 CI on GitHub (`.github/workflows/android.yml`)
**CI** (continuous integration) is a clean computer on GitHub that rebuilds and checks the whole app from scratch. It runs:
- on every push to `main`;
- on every version tag (`v…`);
- on every pull request;
- whenever someone starts it by hand.

If a newer push arrives, the older run is cancelled. Results are at github.com/thejas17d/SpaceView → **Actions**. A green tick means every step passed.

| Step | What it checks, in plain words | If it fails |
|---|---|---|
| Checkout, set up Java 21, set up Gradle | Downloads the code and installs the tools. Gradle's downloads are cached to save time. | Usually a GitHub hiccup. Press "Re-run jobs". |
| Make gradlew executable | Lets the build start button run on Linux. | Rare. |
| **Unit tests, lint and debug build** | Runs all tests, the engine coverage check (≥ 90%), lint on `ar`, `data` and `app`, and builds the debug APK. | See 4.1 and 4.2. Download the **reports** artifact for details. |
| **Dependency guard** | Compares every library inside the release app against the approved list `ci/release-dependencies.txt`. It also blocks forbidden extras (ad "mediation" add-ons, the 12 MB `filamat` tool, the old Lite ads library). This stops an unreviewed library from sneaking in. | If the change was intended (for example a library update), a builder regenerates the list with `ci/dependency_guard.py update` and commits it. If not, remove what crept in. |
| Decode release keystore | If the secret signing key is stored in GitHub, it unpacks it. Otherwise it warns "signed with a throwaway debug key". This step is skipped for pull requests. | The warning is fine for testing. It is not fine for the Play Store. |
| **Release APK and AAB** | Runs release lint, then builds the shrunk release APK and the AAB for Play. On a version tag it **refuses** to build without real ad IDs, a real signing key, a support e-mail and a privacy-policy link. It warns when test ads are used. | Add the missing secrets in GitHub → Settings → Secrets. |
| R8 metadata | Pulls the shrinker's summary file out of the AAB, for size analysis. Only a warning if it is missing. | Can be ignored. |
| Locate build tools | Finds Android's command-line tools for the next checks. | Rare. |
| **16 KB page-size check** | From 1 Feb 2027, Google Play refuses app updates whose built-in native code isn't laid out for phones with "16 KB memory pages" (newer Android phones). This step checks every native library in the APK with `zipalign` and `ci/check_native_libs.py`, and blocks `filamat`. | Usually caused by a library update that shipped old-style native code. Update or replace that library. |
| **Permission allowlist** | Lists every permission in the final release app and compares it to `ci/allowed-permissions.txt`. A new, unexpected permission (which users and Play's privacy form would see) fails the build. | If the new permission is wanted, add it to the list *and* to the Data safety form. If not, remove it in the manifest. |
| **Size budget (arm64)** | Uses Google's `bundletool` to compute what a typical modern phone (`ci/device-spec-arm64.json`) would actually download from Play. **Fails above 18 MiB** download or 30 MiB installed. The target is 15 MiB, and the last measure was **10.07 MiB**. It prints a size table on the run's summary page. | Find what grew (new library, images, 3D assets). Shrink it or decide to raise the budget in `android.yml` (`SIZE_BUDGET_BYTES`). |
| Upload artifacts | Saves the outputs for download: `spaceview-debug-apk`, `spaceview-release` (release APK, AAB and the R8 `mapping.txt` needed to read crash reports), `r8-metadata`, and `reports` (test and lint results, always uploaded, kept 7 days). | — |

### 4.4 Review → gate → commit
Every milestone follows the same loop (from `docs/PLAN.md` §5):
1. **Builders** write the code, in separate modules so they don't collide, and run the tests locally.
2. The **Reviewer** reads the change and writes `docs/reviews/Mx-review.md`. Each problem is graded:
   - **BLOCKER**: must not ship.
   - **MAJOR**: must be fixed before the gate.
   - **MINOR**: should be fixed, but does not stop the milestone.
3. Builders fix the problems and add a test for each one, so it can't come back.
4. The **Judge** checks every finding against the evidence and writes `docs/reviews/Mx-gate.md`, with the verdict PASS, PASS WITH CONDITIONS, or FAIL.
5. Only after a pass is the work **committed** (saved as a named snapshot in git) and **pushed** to GitHub, where CI must turn green.
6. Test APKs for you are built from a *clean copy* of that exact commit, so unfinished work can't leak into them, and placed in `builds/`.
7. You run the device checklist in `docs/TESTING.md` on a real phone. Some things, like real accuracy and real AR, can only be checked there.

### 4.5 Device checklist
`docs/TESTING.md` lists what only a human with a real ARCore phone can confirm. Examples: the camera starts and stops cleanly, the ± is honest against a tape measure, ads never cover the camera, and History survives an upgrade. Report each item as ✅ or ❌ with a note.

### 4.6 The build lock
This PC has only about 2 GB of free memory, and two builds at once would crash both. So:
- Before building, an agent creates a folder named `.build.lock`. Creating a folder is all-or-nothing, so only one agent can win.
- If the folder already exists, someone else is building: wait and do other work.
- When the build ends, the agent that created the folder deletes it. Only that agent may delete it.
- A lock older than 20 minutes is considered stale.

**If it seems stuck:** check Task Manager for a running `java.exe` (Gradle). If none is running and the folder is old, it is safe to delete `.build.lock`.

### 4.7 APKs vs AABs, debug vs release
| | What it is | Where you meet it |
|---|---|---|
| **APK** | The installable app file. You can copy it to a phone and tap it to install. | `builds/*.apk`, and the CI artifacts. |
| **AAB** (Android App Bundle) | The file you upload to Google Play. Play cuts it into a small, custom APK for each phone (only that phone's language, screen and chip parts), so downloads are smaller. It cannot be installed directly. | CI artifact `spaceview-release`. It is the M6 deliverable. |
| **Debug build** | For testing. It isn't shrunk, so it is big (about 38 MB). It is installed as a separate app (`com.spaceview.app.debug`), always uses test ads, and is signed with a throwaway key. | `SpaceView-M3-debug.apk` |
| **Release build** | Shrunk and optimised by R8 (about 20 MB universal; about 10 MB download per phone from Play). This is what customers get. Today it is signed with the *debug* key and uses test ads, because the real key and ad IDs aren't set up yet. | `SpaceView-M3-release.apk` |
| **Signing** | A digital signature that proves who made the app. Play accepts updates only if they carry the same key, so the real release key (a "keystore" file plus passwords) must be **kept safe forever** and never committed to git. It will be stored as GitHub secrets. | Coming, before M6. |

Install a new build **over** the previous one (don't uninstall first) when you want to test that saved data survives the upgrade.

---

## 5. Glossary

- **± / uncertainty / 95%:** The "plus or minus" after every result. "120 cm ± 2" means the true length is very likely between 118 and 122 cm. "95%" means that, for a fair ±, about 19 out of 20 real measurements land inside that range. SpaceView rounds the ± up, never down.
- **AAB (Android App Bundle):** The file uploaded to Google Play. Play turns it into small per-phone APKs. It can't be installed directly.
- **AdMob:** Google's advertising service. SpaceView uses it for banner and interstitial ads.
- **Anchor:** A pin that ties a point to a real spot in the room, so it stays put while ARCore refines its map.
- **APK:** The installable Android app file.
- **AR (augmented reality):** Drawing virtual things (lines, labels, 3D objects) on top of the live camera view, so they appear to sit in the real room.
- **AR Required:** A Play Store setting. Only ARCore-capable phones can install the app, and Play installs the AR services with it.
- **ARCore:** Google's AR system. It combines camera and motion sensors to know where the phone is in the room, and finds surfaces.
- **Artifact:** A file that a CI run saves for download, such as an APK, an AAB or a test report.
- **Badge (Good / Fair / Rough):** A quick quality grade from the ±. Good means within about 1% or 5 mm; Fair means within about 3% or 2 cm; anything else is Rough.
- **Banner ad:** A small ad strip at the bottom of a normal screen.
- **Bench test:** Measuring known objects (A4 sheet, door, table) on your phone to check that the ± is honest, and to tune the accuracy constants. It is pending.
- **Branch:** A separate line of work in git. The main line is `main`.
- **Build:** Turning the source code into an installable app.
- **Calibration:** Measuring something whose true size you know, so the app can correct its scale. In v1, this is a span of 1 to 5 m checked with a tape.
- **CI (continuous integration):** An automatic clean rebuild and check of the whole project on every change (section 4.3).
- **Commit:** A saved, named snapshot of the project in git. You can always go back to it.
- **Coroutine:** Kotlin's way of doing slow work (saving files, maths) in the background without freezing the screen.
- **Coverage / Kover:** The percentage of code lines the tests actually run. Kover is the tool that measures it; the engine must stay at 90% or above.
- **DAO:** A fixed menu of allowed database actions, such as "add", "list newest" or "delete".
- **Database:** An organised on-phone store of records. SpaceView's is `spaceview.db`, with tables for History and Gallery.
- **DataStore:** Android's tool for small settings (units, sounds, calibration, ad counters), kept in tiny files.
- **Debug vs release:** A debug build is for testing: big, unshrunk, with test ads. A release build is shrunk and optimised for customers.
- **Debug vs release signing:** A debug key is a throwaway key every developer has. The release key is the private key that proves future updates come from you. Lose it and you can't update the app.
- **Dependency:** An outside library the app uses, listed in `gradle/libs.versions.toml`.
- **Depth (Depth API):** On supported phones, ARCore estimates how far away each pixel is. This gives steadier points on plain surfaces. It can be switched on or off in Settings.
- **Device tier:** SpaceView's grouping of phones by sensing ability (depth sensor, depth by software, no depth). It sets each phone's expected error.
- **Entitlement:** The record that says what a user has paid for (for example "Pro active until 5 Dec"). The app checks it before unlocking paid features. **Coming** with Pro.
- **FileProvider:** An Android gatekeeper that lets another app (such as WhatsApp) read one specific file you chose to share, without opening up the rest of the app's storage.
- **Filament:** Google's 3D drawing engine. SceneView uses it to draw 3D on the camera view.
- **Freeze-frame:** Pausing the camera picture so you can fine-tune a point with your finger and a magnifier.
- **Gate:** The milestone checkpoint where the Judge decides PASS or FAIL against the plan's rules.
- **GitHub Actions:** GitHub's service that runs the CI steps in `android.yml`.
- **GLB:** A standard single-file 3D model format. SpaceView writes true-size boxes as GLB, where 1 unit is 1 metre.
- **Gradle:** The build tool that compiles the code, runs tests and packages the app.
- **Homography / rectify:** The maths that turns a photo taken at an angle into a straight-on, flat picture.
- **Interstitial:** A full-screen ad shown between tasks. SpaceView strictly limits it (see `AdPolicy`).
- **Jetpack Compose:** Android's modern way to build screens in Kotlin code instead of layout files.
- **JVM:** The engine that runs Kotlin and Java code on a computer. It is why the tests can run without a phone.
- **Kotlin:** The programming language the app is written in.
- **Lint:** An automatic proof-reader for Android code and resources (section 4.2).
- **Manifest:** The app's ID card (`AndroidManifest.xml`): its name, icon, permissions and requirements.
- **Mesh / TSDF:** A mesh is a 3D surface made of tiny triangles. TSDF is a method for fusing many depth readings into such a surface. Together they power the rough on-device 3D scan, which is **coming** and still proposed in `ARCHITECTURE-v2.md`.
- **Migration:** A small script that upgrades a user's existing database to a new layout without losing their saved data.
- **Milestone (M0–M6):** A planned chunk of work with its own gate. M0 to M3 are done, and M5 is partly done.
- **Module:** One self-contained part of the project (`engine`, `ar`, `data`, `app`).
- **Permission:** Something the app must declare, and sometimes ask the user for, such as the camera. CI checks the list.
- **Plane:** A flat surface (floor, table, wall) that ARCore or SpaceView has detected.
- **Play Billing:** Google Play's system for in-app purchases and subscriptions. It is planned for Pro and is **coming**.
- **Pose:** Where something is *and* which way it faces. The phone's pose changes as you move it.
- **ProGuard rules:** Instructions telling R8 which code it must not remove or rename.
- **Pull request:** A proposed change on GitHub that CI checks before it is merged into `main`.
- **Push:** Uploading local commits to GitHub.
- **R8:** The release-build shrinker. It removes unused code and shortens names, so the app is smaller and faster.
- **RANSAC:** A method that fits a shape (here, a flat surface) to messy data by repeatedly guessing and keeping the guess most points agree with. It ignores strays.
- **Regression test:** A test written for a bug that was fixed, so the bug can never quietly come back.
- **Repository (code):** In the code, a "front desk" class that hides the details of storing and loading data. On GitHub, "repository" means the project's online home.
- **Resource:** A non-code part of the app: text, colours, icons, XML settings.
- **Room:** Android's database library. It manages `spaceview.db`, its versions and migrations.
- **SceneView:** The library that shows the AR camera view and 3D content in Compose. It uses ARCore for tracking and Filament for drawing.
- **Schema:** The layout of a database: its tables and columns. Saved as `data/schemas/*.json`.
- **16 KB page size:** A memory layout used by newer Android phones. From 1 Feb 2027, Play blocks updates whose native code isn't ready for it. CI checks for this.
- **StateFlow:** A live "current value" that screens watch. When it changes, the screen redraws.
- **Tracking:** ARCore knowing where the phone is. It can be lost when moving too fast, in the dark, or facing a blank wall.
- **UMP (User Messaging Platform):** Google's consent form for ads, shown where privacy law requires it, before any ad loads.
- **Unit test:** An automatic check of one small piece of code (section 4.1).
- **ViewModel:** The "brain" behind a screen. It holds the screen's data and survives rotation, while the screen itself only draws.

---

## 6. If you want to change X, look at Y

| You want to change… | Look at… | Note |
|---|---|---|
| Any app text | `app/src/main/res/values/strings.xml` **and** `values-hi/strings.xml` | Change both, or lint fails. Hindi should be reviewed by a native speaker. |
| Colours | `app/.../ui/theme/Color.kt` (app screens), `res/values/colors.xml` and `values-night/colors.xml` (start-up background and icon), and `measure/DimensionPainter.kt` (AR lines) | Keep one signal yellow for actions. |
| Fonts and text sizes | `ui/theme/Type.kt` | Bundling a font adds size. |
| App icon | `res/drawable/ic_launcher_*.xml` and `res/mipmap-*` | — |
| Ad frequency | `app/.../ads/AdPolicy.kt` → `AdRules` (3 free saves, 1 per 3 saves, 3 min gap, 4 per day) | `AdPolicyTest` must be updated to match. |
| Where banners appear | `BannerAd(...)` calls in `HistoryScreen.kt`, `GalleryScreen.kt` and `SettingsScreen.kt` | Never on camera screens. |
| Real ad IDs, signing key, support e-mail, privacy link | GitHub → repository Settings → Secrets (`ADMOB_*`, `KEYSTORE_*`, `SUPPORT_EMAIL`, `PRIVACY_POLICY_URL`), read by `app/build.gradle.kts` | Never put them in the code. |
| Good / Fair / Rough thresholds | `engine/.../Uncertainty.kt` → `Quality` (`GOOD_REL` 1%, `GOOD_ABS` 5 mm, `FAIR_REL` 3%, `FAIR_ABS` 2 cm) | Tune only from bench-test data. |
| Expected error per phone type | `Uncertainty.kt` → `DeviceTier` (`scale95` ±1.5% / 2% / 0.8%, `absFloor95` 1 cm / 1 cm / 5 mm) | This is the core honesty setting. |
| Aiming error | `ar/.../AimModel.kt` → `AIM_PIXELS_VGA` (2 px) | — |
| How long to hold still before a point is ready | `engine/.../PointEstimator.kt` → `PointEstimatorConfig` (`minSamples` 15, `windowSize` 30) | — |
| "Too fast" and "too dark" limits | `ar/.../TrackingGate.kt` → `TrackingGateConfig` | — |
| Calibration limits | `engine/.../Calibration.kt` → `Calibrator` (factor 0.9 to 1.1); `MeasureViewModel.kt` → `KNOWN_LENGTH_MIN_M` / `MAX_M` (1 to 5 m); `MeasureSettingsRepository.kt` → `MAX_AGE_MILLIS` (30 days) | — |
| Automatic front-photo rules | `ar/.../FrontCaptureGate.kt` (`MAX_ANGLE_DEG` 15°, 25° for boxes, `HOLD_NS` 0.6 s) | — |
| Preset depths for frames and TVs | `app/.../scan/ScanDraftBuilder.kt` (`FRAME_DEPTH_M` 3 cm, `TV_DEPTH_M` 8 cm) | — |
| Unit choices by context | `engine/.../Units.kt` (`defaultUnits`) and `app/.../measure/MeasureFormatter.kt` (1.5 m room, 15 cm small object) | — |
| History length | `data/.../history/MeasurementRepository.kt` → `HISTORY_LIMIT` (200) | — |
| Gallery item size and photo quality | `GalleryRepository.kt` → `MAX_SCAN_BYTES` (0.5 MB), `FRONT_LONG_SIDES`; `JpegEncoder.kt` (quality 90 / 82) | The 0.5 MB budget is a milestone rule. |
| Anything saved in the database | `data/.../SpaceViewDatabase.kt` plus the entity file, a **new migration**, a new `schemas/N.json` and `SpaceViewMigrationTest` | Otherwise users lose data on update. |
| The download-size budget | `.github/workflows/android.yml` → `SIZE_BUDGET_BYTES` (18 MiB) and `INSTALLED_BUDGET_BYTES` (30 MiB) | PLAN target is 15 MB. |
| Allowed permissions | `app/src/main/AndroidManifest.xml` **and** `ci/allowed-permissions.txt` | Also update Play's Data safety form. |
| A library or its version | `gradle/libs.versions.toml`, then regenerate `ci/release-dependencies.txt` | Otherwise the dependency guard fails. |
| Languages offered | `app/build.gradle.kts` → `localeFilters`, plus a new `res/values-xx/strings.xml` | — |
| The app's version number | Set automatically by CI (`VERSION_CODE` = run number, `VERSION_NAME` = 0.1.run) | — |
| What Google backup copies | `res/xml/backup_rules.xml` and `data_extraction_rules.xml` (keep them in sync) | Calibration must stay excluded. |
| Screens and the routes between them | `app/.../nav/AppNavHost.kt` | — |
| Onboarding pages | `onboarding/OnboardingScreen.kt` and the `onboarding_*` strings | — |
| What the Build Book includes | `docs/build_html.py` (`DOCS` list and `MILESTONES`), then run `python docs/build_html.py` | Never edit `SpaceView.html` by hand. |
