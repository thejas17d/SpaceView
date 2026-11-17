# SpaceView — Real-device test checklist (M2: Measure mode)

AR cannot run on an emulator, so these must be checked on a real ARCore phone. Until the GitHub repo exists, APKs are built locally and placed in `C:\Users\Kanishk\Desktop\Dev\SpaceView\builds\` (newest file = current build; a `-release` APK is there for item 13). **Discard any results from `SpaceView-M2-debug.apk` (commit fbe1479): its ± was too small.**

Report each item as ✅ / ❌ + a note (and a screenshot or screen recording if something looks wrong).

## 1. Install and first run
- First launch shows onboarding (3 pages, Skip goes to the accuracy note).
- Opening Measure asks for the camera with our explanation first. Deny → "Don't ask again" → an **Open settings** button appears.
- On a phone with missing/old Google Play Services for AR, the install prompt appears and returns to the app.

## 2. Session lifecycle
- Camera starts. Home button, app switch, lock/unlock → it resumes.
- Leaving the measure screen turns the camera indicator off.
- Enter/leave AR 10 times — app stays smooth (optional: `adb shell dumpsys meminfo com.spaceview.app.debug` before/after).
- Rotating the phone does not restart the session.
- Place 2–3 points, then leave the measure screen — repeat **20 times**. The app must never crash or freeze (this checks anchor clean-up on exit).

## 3. Coaching and tracking states
- "Move slowly" coaching until tracking starts.
- Waving the phone → "Move more slowly".
- Dark room → low-light chip. Plain wall → texture hint.
- Cover the lens after placing points → "Point back…" with Start over.

## 4. Reticle
- Stability ring fills, centre dot appears when steady. No surface → dashed grey ring. Distance readout looks sensible.

## 5. "+" button and sounds
- Pressing "+" while moving → "Hold steady", then the point is placed.
- Soft click per point, double tone on completion, **no vibration**. Settings → Sounds off silences them.

## 6. Accuracy bench check (most important)
Use known objects; measure each a few times at ~0.5 m, 1 m, 2 m:
| Object | True size | Mode |
|---|---|---|
| A4 sheet long side | 29.7 cm | Distance |
| A4 sheet | 21.0 × 29.7 cm | Rectangle |
| Bank card | 8.56 × 5.40 cm | Rectangle |
| A door width | measure with a tape | Distance |
| A table | tape height | Height |
| A photo frame | tape | Rectangle |
Check: the true value is usually inside the shown ±; the Good/Fair/Rough badge feels honest; numbers aren't shown more precisely than the ±.

## 7. Live dimension line
- Yellow line and end ticks stay glued to the surface while you move. Rectangle's 4th edge is dashed. Height mode shows a vertical line.

## 8. Freeze and magnifier
- Frozen image lines up with the live view (also on tall screens).
- Dragging moves the last point at half finger speed; the loupe shows 2×; Done re-anchors and the value updates.

## 9. Plain wall
- On a plain wall with no detected surface, points stay stable (our fitted wall plane) instead of jumping.

## 10. Depth (later, once the Settings toggle exists)
- No stutter with depth on; wall points improve.

## 11. Calibration
Calibration is a **known length**: a straight span you measured with a tape, 1–5 m, both ends on one flat surface. Card / A4 are hidden (`MeasureViewModel.CALIBRATION_SMALL_REFERENCES_ENABLED = false`): with the 1 cm tier floor the engine rejects them every time. Re-enable them only after the bench test justifies a smaller floor.
- Picker shows only "Known length". Measure a tape-measured 2–3 m span (table edge, or a door's width along the wall), type the tape value: cm, or inches with the Imperial unit setting.
- Typed values outside 1–5 m (100–500 cm, 39.4–196.8 in) and non-numbers show the range error; nothing is calibrated.
- The span's AR length is **never** shown during calibration: no live length under the reticle while aiming (only the camera-to-target distance pill), none on the freeze-frame loupe, none on the entry card (so it cannot be typed back in).
- A 2 m+ span held steady: usually accepted with a factor near 0% and ± under about 1%. A 1 m span: usually "Too uncertain" (expected on non-LiDAR phones).
- Ends on two different surfaces (e.g. door jamb to jamb across the opening): "Both ends must be on the same flat surface".
- After applying: the "Calibrated" chip shows only on readings whose points are all on that same surface; a floor → table height or floor → wall reading is **not** calibrated.
- Calibration applies only in the AR session that made it. Leave Measure and come back, or press "Try again": readings are no longer calibrated. Rotating the phone or changing font size does **not** restart the session, so the calibration stays active.
- Home / Settings must not present an old calibration as active.
- Settings → Clear calibration removes it; after 30 days Settings marks it as stale.

## 12. Save and History
- Save with and without a name. History shows newest first with ± and badge; delete works.
- Change units in Settings → History reformats.
- Ads (test ads in debug):
  - Leaving Measure **without saving** never shows an interstitial.
  - After 4+ saved measurements in total, leaving Measure after a save may show a test interstitial — only once the Home screen has fully appeared, **never over the camera**.
  - The History screen shows a test banner fixed at the bottom, at least a finger-width from any button.
  - If a test interstitial is a **video** ad, it plays to the end without freezing (the WAKE_LOCK permission was removed — this confirms it isn't needed).
  - Settings: depth toggle, calibration date (and "over 30 days old" warning), Clear calibration with confirm.

## 13. Release build
- Repeat a quick flow on the release APK (catches R8 shrinking crashes).

## 14. Performance
- 10-minute session stays smooth; the phone doesn't get very hot.

## 15. Hindi
- Set the phone language to Hindi: text fits on AR chips and cards.

## 16. M3: Scan → Gallery (from `docs/reviews/M3-gate.md` §6)

Install `builds/SpaceView-M3-debug.apk` **over** the M2-final APK, without uninstalling first, so that item 1 tests the database upgrade.

**Upgrade and data**
1. **v1 → v2 upgrade:** install the M3 APK over M2-final with saved History rows. The rows survive with the same values, and Gallery opens empty. No crash on Home, History or Gallery.

**Front photo**

2. **Auto photo** is taken within about 1 s of holding still and parallel. Test on a 60 Hz phone **and** a 90/120 Hz phone (scan B1).
3. **The photographed face is the face whose width is shown:**
   - Box: finish a long cabinet standing near one end of its long face. The Outline card shows W = the long side, and the photo step guides you to that face (scan M1).
   - TV or frame: the Review photo is upright and unmirrored in portrait and in landscape.
4. **Pause in step 3** (power button or a call), then resume. "The outline was lost…" appears, no photo is taken, and **Measure again** and **Skip photo** both work (scan M2).
5. **A low carton on the floor** can be captured at 25° without crouching. If it cannot, note the angle you needed (`MAX_ANGLE_DEG_BOX`).
6. **Steadiness:** a normal hand-held hold reaches HOLDING. Tune `STEADY_MAX_*` and `HOLD_NS` from what you see.
7. **Box top tapped on the floor** next to the box: rejected with "aim at the top" (scan m7).

**Review, Gallery and edits**

8. **The Review number equals the Gallery number:** W/H/D and the TV diagonal ± are identical on Review, Saved, the item screen and the share text.
9. **Edit a side** in the Gallery: the value shows "~" and no ±, and the diagonal loses its ±. Kill the app right after the edit (swipe away): the item still opens, and **Place** (M4) later uses the edited size.
10. **Share** from Saved and from the item screen to WhatsApp: the photo and the size text arrive. Delete the item: its share copy is gone.
11. **Thumbnail and photo quality:** note the Review photo's sharpness on this phone (the CPU image is often 640×480). This is input for M6's high-resolution still.

**Ads**

12. **Ads (scan flow):**
    - Save, then **Place**: no ad.
    - Save, then **Share**, then **Done**: at most one ad, over the Gallery.
    - Save, then system **Back**: at most one ad, over the screen you came from.
    - Back without a save: never an ad.
    - An ad never appears over the camera or on the Saved screen itself.

**Process death and Hindi**

13. **Process death:** on Saved, enable "Don't keep activities" and switch apps, then return. You land back on Saved for the same item.
14. **Hindi:** run the whole scan flow (type picker → Saved) in Hindi. No English left, and no truncated buttons.

**History fields**

15. History rows saved from Measure now carry the ARCore version, device tier, calibration factor and raw length (check in the database).
16. If a USB phone with debugging is available, run `~/.android-toolchain/g.sh :data:connectedDebugAndroidTest`, which runs the Room migration test.

## 17. Pro and billing (P1; internal track with licence testers)

**Release gate (must pass before promoting any build with real products)**

1. **The licence key is this app's (P1 review MINOR 5):** a licence tester buys `pro` / `monthly` on the internal track and Pro turns on within seconds. The build only checks that `PLAY_LICENSE_KEY` parses as a 2048-bit RSA key; a key from another app would charge testers, never grant Pro, and Play would refund after 3 days.
2. Restore after a reinstall gives Pro; a second Google account on the same phone gets no Pro.

**Purchases**

3. Monthly; yearly with the 7-day trial (new account only); the 30-day pass.
4. Slow test card: pending, then approved, unlocks Pro. A declined card leaves Free. Cancelling Play's sheet is silent.
5. Cancel the subscription in Play: Pro stays until the period ends. Refund from Play Console: Free at the next start.

**Ads and Pro (P1 review MAJOR 1)**

6. A Pro user never sees the UMP consent form or an ad, including: a cold start after 8+ days away, a fresh reinstall, and a slow network (Play answering after several seconds). Check in `adb logcat` that `MobileAds` never initialises.
7. Buying Pro removes the History/Gallery banner at once; no interstitial after the next save.

**Gallery cap (P1 review MAJOR 3)**

8. Free with 20 items: "Scan object" (Home and Gallery) opens the paywall *before* the camera. With Pro the scan starts.
9. Free, reaching 20 between starting a scan and Save (e.g. Pro lapsed meanwhile): Save shows "Gallery full" with the scan still on screen. Share sizes works; Get Pro, buy, come back, Save stores it; Discard asks first.
10. Lapsed Pro with 30+ items: every item still opens, places, shares and deletes.

**Offline grace**

11. Airplane mode within 7 days: still Pro. After 7 days of use offline: Free. Setting the clock back while offline does not bring Pro back (P1 review MAJOR 2).

**UI**

12. Hindi paywall. TalkBack on the Close button, plan radios and terms. At 200% font size the Close button stays visible while scrolling. Manage subscription opens Play.

**Cold start**

13. `am start -W` three times on a release build, before and after P1: no regression (no billing or ads object is built before the first frame).

**Play Console: Data safety (P1 review MINOR 10; decide at submission)**

- **Purchase history:** the app stores only a SHA-256 hash of the purchase token, on the device, excluded from backup and never sent anywhere by SpaceView. Google Play Billing handles the purchase itself.
- **Diagnostics from SDKs:** Play Billing Library 9.1 brings `com.google.android.datatransport` (Firelog), which sends library diagnostics to Google. Check Google's current Play Billing Library data disclosure and declare what it lists, together with the AdMob and UMP rows.
- **Permission:** `com.android.vending.BILLING` (install-time, no prompt).
- Re-check the form whenever `ci/release-dependencies.txt` changes in `com.android.billingclient` or `com.google.android.datatransport`.

## 18. M4 Place in room + 3D core (from `docs/reviews/M4-P1-gate.md` §7)

Install the M4 APK **over M3**, without uninstalling, so the v2→v3 database upgrade gets tested.

**Upgrade and data**
1. **v2 → v3 upgrade (mandatory first):** install the M4 APK **over** M3 on a phone with several gallery items (TV, frame, box) and History rows.
   - Every item still opens, shares and deletes, and History is intact.
   - Placing each M3 item once regenerates its model, which happens silently. The item still opens after an app restart.

**Place**

2. **True scale:**
   - Place a gallery item next to the real object, or a box you scanned next to the box itself. The outline matches within the item's ±.
   - The "1:1 True size" chip is shown, and pinching never scales.
3. **Surfaces:**
   - Floor, table and wall.
   - A frame or TV hangs flush and upright. A box stands on its bottom.
   - Move, rotate (90° and twist) and 1 cm nudges behave from several viewing angles.
4. **Plain wall fallback:**
   - On a plain painted wall the fallback is offered after about 5 s. Two taps on the wall–floor line build the wall.
   - Two taps on a table edge are rejected.
   - **Repeat on a polished vitrified or marble tile floor (N1).** Note any wrongly rejected floor taps.
5. **Eye level and slider:** the centre-height readout matches a tape within about 2 cm. The slider value survives "Don't keep activities".
6. **Fit verdict:**
   - Measure a gap about 3 cm wider than a measured item: the card reads Tight (linear band).
   - Measure a gap 10 cm wider: Fits, quoting the smallest spare.
   - A wall rectangle where only the width is too small: "Too big by X · width" (M4 MAJOR 1).
   - A typed size shows "using your typed size".
7. **A floor rectangle** shows "Height not checked".
8. **Pause and process death:**
   - Power button on Place: the item is picked up, and the space is labelled "measured before the pause".
   - Under "Don't keep activities", the target, spin, height and space are restored with the restore note.
9. **Errors:** delete the item while Place is open, in another task or via Back stack tricks. The screen says "deleted" and goes back, with no crash.
10. **Rendering:** the photo face of a placed TV or frame matches the real colours (it is unlit). The sides are lit and not black.
11. **Ads:**
    - Saved → Place → Back → item → Back: no ad anywhere.
    - Gallery item → Place → Back: no ad.
    - Never an ad over the camera.
12. **30 fps:** run `gfxinfo` on Place with a textured item placed, on the low-end and the mid-range phone. Frame time p95 must be ≤ 33 ms. Note any GC pauses in a trace (N5).
13. **Hindi and TalkBack:** the Place screens in Hindi with nothing truncated. TalkBack reads the verdict card in full: verdict, amount, axis and notes.

**3D core (spike, D milestone; depth phone)**

14. **Carton 60×40×40 cm at about 0.7 m and about 1.5 m, 5 scans each.** Record the mesh-extent error against a tape and the ± (decision (a), R2).
15. If a ToF phone is available, repeat item 14 with ToF depth. If not, record the depth source the phone used.
16. **Memory, thermal and time:**
    - 90 s capture from a cool start: peak extra PSS ≤ 40 MB (`dumpsys meminfo`).
    - No SEVERE thermal state.
    - Sofa processing ≤ 5 s.
17. **Sofa about 5 cm from a wall:** no wall patch in the mesh, and D is not inflated (M4 MINOR 8).
18. **Saved scan:**
    - The item is ≤ 0.5 MB (Settings storage, or the app's files).
    - It places standing on the floor at its measured footprint (bottom about 1 cm up).
    - The Gallery shows "Approximate 3D shape · measured size".
19. **Free caps:** with 3 saved 3D scans on Free, the next 3D scan opens the paywall **before** the camera. The 20-item cap behaves as in TESTING §17 items 8–10.

## 19. Furniture templates + 3D Scan Lab spike (from `docs/reviews/T-D0-gate.md` §7)

**T: Furniture templates** (release or debug app; Free first, then Pro through FakeBilling on debug or a licence tester on release)

1. **Entry:**
   - Home → **Furniture** opens the picker.
   - An **empty** gallery shows "Add furniture".
   - A non-empty gallery has no furniture entry (known, N-c).
2. **Free versus Pro templates:**
   - On Free, **sofa, bed and wardrobe** start.
   - **L-sofa, dining table, dining chair, TV unit, bookshelf and study table** open the paywall **before** any camera.
   - On Pro, all nine start.
3. **Guided measure:**
   - Each W/D/H **Measure** opens a one-shot AR measure and returns its value with ±.
   - **Type** enters an exact value with no ±.
   - Untouched defaults show "typical".
   - Save stays disabled until W, H and D are each measured or typed.
   - Invalid values (seat above the height, for example) show an inline error.
4. **Preview:**
   - The 3D preview follows the options: seats, doors, shelves, chaise side.
   - The L-sofa's left and right are mirror images.
   - The overall size matches the entered W×H×D.
5. **Review and Save:**
   - Review shows the "overall box" note. Save → Saved.
   - Placing the item stands it on the floor with a W×D footprint.
   - Next to the real sofa or bed, the outline matches within the item's ±.
6. **Verdict:** an L-sofa against a measured gap says "Checked against the overall box (width × depth × height)".
7. **Gallery cap:**
   - On Free with 20 items, the picker opens the paywall before AR.
   - At 19 items, start a template, then make the account lapse (FakeBilling) before Save. Save shows the needs-Pro card and keeps the draft. After upgrading, Save works.
8. **Process death** (Developer options → "Don't keep activities"):
   - Background the app mid-measure, at Review and at Saved.
   - Step, values (with ±), options, preset, palette and name are restored. Saved does not save twice.
9. **Stability:** a saved template looks identical after an app restart and after placing it twice.
10. **Lapse never hides data (ADR-005 rule 2):** save an L-sofa as Pro, then lapse. It still opens, places, shares and deletes.
11. **Ads:**
    - Save → Saved → Done: at most one ad, and only on a non-AR screen.
    - Saved → Place → Back: no ad.
    - Never an ad over the camera.
12. **Hindi, TalkBack and 200% font:**
    - The picker, guided measure and Review are in Hindi with nothing truncated.
    - TalkBack reads each parameter row, with its value and source.
    - Targets are ≥ 48 dp.

**3D Scan Lab spike** (debug APK `builds/SpaceView-TD0-debug.apk` from R3: package `com.spaceview.app.debug`, which installs **beside** the release app; depth phone)

13. **Setup:**
    - Install the APK, then open Settings → the debug panel → **3D Scan Lab**.
    - Depth must show a source (RAW, HARDWARE or SMOOTHED). Note which.
    - Start each block cool, in good even light.
    - Tape the carton (about 60×40×40 cm) and type its W/H/D on the intro screen.
14. **Carton at about 0.7 m, 5 scans** (label `carton-0.7m`):
    - Mark the box: 3 base corners, then the top.
    - Tap Start, walk slowly all the way round for 45–90 s, then Finish.
    - Record `c1_mesh_within_pm_of_box` and `mesh_minus_tape_*`.
15. **Carton at about 1.5 m, 5 scans** (label `carton-1.5m`), with the same tape values (R2).
16. **Armchair, 5 scans,** and **a 2–3-seat sofa about 5 cm from a wall, 5 scans,** each taped. Check:
    - there is no wall patch in the mesh viewer;
    - D is not inflated;
    - `c4_processing_le_5s`.
17. **One full 90 s scan from a cool start:**
    - `stop_reason = TIME_CAP`;
    - record `thermal_max` and `c3_no_severe`;
    - record `peak_extra_pss_mb`.
18. **A second phone,** ideally a smoothed-depth-only one: repeat items 14 and 15.
19. **Harness edge cases:**
    - **Back during "Starting…":** the next run's numbers are clean (`ar_frames` is about 90 s worth, not doubled).
    - **Start while the phone is hot:** "too warm" is shown, with no dead capture.
    - **App switch mid-capture:** the capture stops with `SESSION_PAUSED` and processes what it captured.
20. **Save one sofa scan from the lab:**
    - the item is ≤ 0.5 MB;
    - it places on the floor at the measured footprint (bottom about 1 cm up);
    - it shows in the **debug** app's gallery only.
21. **Share all results:** send `spaceview-scan3d-runs.csv`, plus 2 or 3 `metrics.json` files and GLBs.
22. **Glossy floor (G2 and N1)** (release app, Place):
    - On a polished vitrified or marble floor, do the plain-wall fallback twice: once with a coffee table in view, once without.
    - Note any wrongly rejected wall–floor taps and the centre-height readout.

**How to read the CSV** (orchestrator or FRIDAY; this goes into ARCH §7.1.10 under R2):
- **Valid run:**
  - `stop_reason` is `USER` or `TIME_CAP` (a `THERMAL_SEVERE` run counts for criterion 3 only);
  - `capture_s` ≥ 45;
  - `glb_error` is empty.
- **Criterion 1:** a set passes if 4 of 5 valid runs at one distance have `c1_mesh_within_pm_of_box = true`.
- **R2:** plot `mesh_minus_tape_*` against `mean_range_m`. If the error grows with range beyond the ±, add the per-`DepthSource` range term.
- **Debug versus `lab`:** apply the §4 table.
- **Camera-side drops (G4):** a `FRAME_TIME` event with a healthy `ar_frame_ms_p95` and `camera_frame_ms_p95` ≈ 66 ms is a light problem, not app load.
- **Unsettled baseline (G3):** if the JSON's `noise` stage `pss_mb` is below the processing baseline − 1 MB, ignore that run's c3.
- **Intrinsics before depth:** `aspect_mismatch = true` combined with a c1 failure means check intrinsics before blaming depth.

---

## Values to tune from results
Aim error 2 px, motion gates, RANSAC limits, Good/Fair/Rough thresholds, device-tier ± floors (1 cm / 5 mm), calibration acceptance.
