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
- **ML Kit object naming (SM1b; unbundled image labelling through Google Play services, `play-services-mlkit-image-labeling` + `com.google.mlkit:*`; SM1-M5 review MAJOR 2):** inference runs on the phone and the photo is never uploaded, but ML Kit sends **diagnostics and usage analytics** to Google. Google's ML Kit data disclosure (developers.google.com/ml-kit/android-data-disclosure, *last modified 14 May 2025*, read on 2026-11-17) lists, for all features:
  - device information (manufacturer, model, OS version and build, available ML accelerators);
  - application information (package name, app version);
  - **device or other IDs**: for "thin" (Play services) features, a device identifier plus per-installation identifiers;
  - performance metrics (latency), API configuration (image format and resolution), input/output size, feature version, event type (initialisation, model download, detection, release) and error codes.
  Per that page: encrypted in transit (HTTPS) and **not transferred to third parties**. Planned answers: **App info and performance → Diagnostics** and **Device or other IDs**, collected, not shared, purpose *Analytics / App functionality*, not user-provided content. **Verified** from the page text above. **Still to check at submission:** the live page again (it may have changed), whether the non-custom Play services labeller adds anything (the page has a row only for the *custom* labeller), and whether Play's "data processed ephemerally / on device" wording applies to the labels.
- **In-app review** (`com.google.android.play:review`, M5): no data declared by SpaceView; the review card is Play's own UI. Check Play Core's disclosure at submission.
- **One consistent story:** the form, the privacy policy and the in-app Help/About text (`help_privacy_*`, "your measurements and photos stay on your phone; Google services used for ads, purchases and object naming may collect limited diagnostic and device data") must say the same thing.
- Re-check the form whenever `ci/release-dependencies.txt` changes in `com.android.billingclient`, `com.google.android.datatransport`, `com.google.mlkit`, `com.google.android.gms:play-services-mlkit-*`, `com.google.android.play` or `com.google.android.gms:play-services-ads`.

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
19. **Free caps:** Free gets **5 3D scans in total** (user decision 2026-11-23). After 5 saved scans, the next 3D scan opens the paywall **before** the camera, and **deleting a scan does not give one back** (the paywall says so). Delete my data keeps the count. The 20-item cap behaves as in TESTING §17 items 8–10.

## 19. Furniture templates + 3D Scan Lab spike (from `docs/reviews/T-D0-gate.md` §7)

> **Parked (PLAN v3, 2026-11-24):** furniture templates and the 3D Scan Lab are not in v1; they live on branch `next/3d-engine`. Run this section only on a build of that branch. For v1, see §22 (legacy template / mesh items must still open and place).

**Spike phone (user, 2026-11-23): Google Pixel 10a.** It is on Google's ARCore list with **Depth API support**; it has no hardware ToF sensor, so its depth is software (motion stereo). Record its results as the non-ToF case of ARCH §7.1.10. A ToF phone is still wanted for comparison but is not required.

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

## 20. M5 remainder: consent, review prompt, Help, Delete my data, accessibility (Builder B, Phase 3 RC)

Release-signed build installed **over** the previous one, unless a step says otherwise.

**UMP consent (needs a UMP test device; debug geography EEA, see the UMP "test your implementation" guide):**
1. Fresh install as an EEA user, Free: the consent form appears shortly after the first frame, before any banner. While it is open, no banner loads and no interstitial is requested (logcat: no `Ads` load lines until the form closes).
2. Consent → banners appear on History / Gallery / Settings. Settings → Privacy → "Ad privacy options" is **enabled** and opens the UMP privacy form.
3. Refuse / manage → no ads load at all; the app keeps working.
4. Non-EEA geography: no form; the "Ad privacy options" row is disabled with "No ad privacy choices are needed in your region".
5. Airplane mode at first launch: no form, no ads. Turn the network on, then open Measure and come back to Home: the form appears on Home (retried when a non-AR screen resumes), **never over Measure, Scan or Place**. Same if the entitlement settles while an AR screen is open, and if Measure or Calibrate is opened while the consent request is still in flight (tap Measure right after Home appears): the form waits for the next non-AR screen (gate G1).
6. Pro (licence tester): the form never appears, and Settings shows no "Ad privacy options" row.

**In-app review (internal-track build only; Play never shows the card to sideloaded builds):**
7. Save 3 measurements or scans, then go back to Home: the Play review card may appear once (Play applies its own quota). Never on Measure / Scan / Place, never while the camera is open.
8. Place an item and get a **Fits** verdict, then go back: the card may appear on the item screen, not over the camera.
9. After an interstitial, no review card for at least 10 minutes. After one ask, none for 90 days (clear app data to retest).

**Help and About:**
10. Settings → Help and FAQ works in airplane mode, in English and Hindi. TalkBack: every question title is a heading (swipe by headings), "±" is read as "plus or minus".
11. Contact: with no support address configured the button opens SpaceView's Play page; with `SUPPORT_EMAIL` set it opens the mail app.
12. Settings → About SpaceView: version and build number match the APK; the licence list scrolls; "Privacy policy" opens the browser when `PRIVACY_POLICY_URL` is set, otherwise "Privacy summary" opens Help.

**Delete my data:**
13. With History rows, Gallery items (scan + template), a calibration and Imperial units: Settings → Delete my data → the dialog lists what goes, says it can't be undone, and lists what is kept (Pro purchase status, ad privacy choices, ad and review counters) → Delete everything. History and Gallery are empty, Settings shows no calibration, units are Auto, "Your data was deleted." is announced by TalkBack. Onboarding does not reappear.
14. As Pro: after deleting, Pro is still active (no ads, Settings shows Pro) without tapping Restore.
15. Rotate or leave Settings while deleting: the delete still finishes (re-open Settings, everything is gone).
    - With a banner showing on Settings, delete: the banner keeps working (SDK and WebView caches are not touched; only SpaceView's own data is removed).

**Accessibility pass (TalkBack, 200% font, Switch Access spot-check):**
16. TalkBack reads measurements in full on Measure results, Review, Gallery cards, the item screen, History, Place notes and the Fit card, and the furniture review: "45.5 centimetres, plus or minus 0.5 centimetres", never "±" or "cm".
17. System font 200% (Display size default and largest): Home, Settings, Help, About, Gallery item, Review, Paywall, Furniture review: no clipped or overlapping text; spec labels wrap instead of truncating.
18. Error and status messages (save failed, invalid size, offers loading/failed, restore result, delete result, Place readings) are announced when they appear.
19. All buttons and rows are at least 48 dp (Accessibility Scanner on Home, Settings, Help, Gallery item, Place).
20. Measure, Scan and Furniture HUDs: mode tabs are at least 48 dp; the Scan AR value rows and the Review dimension rows are read in full ("plus or minus", unit names); the Review edit dialog field has a label (gate G5).

---

## 21. Smart Measure (SM1): hands-free point drop, object naming, G2 floor (Builder A, Phase 3 RC)

Release-signed build installed **over** the previous one, unless a step says otherwise. Hands-free is **on** by default; the haptic tick is **off** (`HAPTIC_TICK_ENABLED = false`, SM1 review MAJOR 3).

**Dwell tuning** (constants in `ar/.../DwellTrigger.kt`: `HOLD_NS` 700 ms, steady ≤ 0.20 rad/s and ≤ 0.10 m/s, drift ≤ max(1 cm, 1% of range), spacing 3 cm, cool-down 1 s):
1. **Shaky hold:** Measure distance, rectangle and height; Scan box (4 points); one furniture step. Hold the phone loosely at arm's length: each point drops once, about 1.2 s after aiming (the ring closes as it drops). Note the count of holds that never drop.
2. **Steady hold:** a tripod or a table edge: a point never drops "too eagerly" while the user is still choosing the spot (note how often Undo is needed in 10 points). Tune `HOLD_NS` within 0.6–0.8 s from items 1–2.
3. **Existing "+" user:** aim, wait about 1 s and tap "+": no "same spot" message, no second point; a tap right after the last point completes never clears the result.

**No unintended drops:**
4. **Calibration:** Settings → Recalibrate. Hold still over a table for 5 s while reading the picker: no click, no point. Choose "Known length": it starts with 0 points, and hands-free is **off** (the HUD switch says so) until switched on.
5. **Freeze:** "+", Freeze, drag the point 4 cm, Done, keep the phone still: nothing drops until the reticle is moved about 3 cm.
6. **Resume:** switch apps mid-measurement and come back holding still: nothing drops within 1 s.
7. **Undo:** after a hands-free drop, Undo and keep holding still: the same spot is not dropped again; move 3 cm and it drops.
8. **Dialogs and cards:** with a dialog over the camera (the camera-permission dialog, the notification shade, or any other system dialog; the consent form never opens over the camera, §20 item 5), on a result card, on the Scan photo step and the Outline card: nothing drops.

**The haptic tick experiment** (debug build with `HAPTIC_TICK_ENABLED = true`; the release keeps it off):
9. On a bench distance of about 1 m, measure 5× hands-free with the tick and 5× with "+". Read each result again after 10 s. If the hands-free spread, or the change after 10 s, is worse than with "+", or the reticle visibly jumps at the tick, the tick stays off for good; otherwise record the numbers and decide.

**TalkBack:**
10. Every hands-free drop is spoken ("Placed point 1", "Placed point 2"), including the **first** one and a point re-dropped after Undo.
11. While aiming, TalkBack does not stream the distance; the live length is spoken once it settles. The hands-free switch is read as a switch with its state. Freeze: the four "Move point" actions move it about 1 cm each, and the new value is spoken.
12. Scan Review: the name chip is announced when it appears.

**Object naming (ML Kit, unbundled):**
13. **Model not yet downloaded:** a fresh install, Scan straight away: no chip, no error, no delay to Save.
14. **Model present** (a few minutes after install, online): a sofa, a chair, a TV and a poster give the right chip within about 3 s, in English and in Hindi. Tapping it fills the name; without a tap the name stays the type's default.
15. **No Play services** (or Play services disabled): no chip, no crash. In airplane mode with the model present: the chip still appears (inference is on-device).
16. **Every ML Kit version bump:** repeat 14 (a change in ML Kit's internal init fails silently; logcat shows one `ObjectNamer` warning).

**Manifest and start-up:**
17. `aapt2 dump xmltree` of the release APK: no `MlKitInitProvider`; the `com.google.mlkit.vision.DEPENDENCIES` = `ica` meta-data is present; `aapt2 dump permissions` equals `ci/allowed-permissions.txt`.
18. **Cold start:** `adb shell am start -W` five times each, against a build of `13f3740`: no regression beyond noise (no ML Kit work at start-up).

**G2 floor (adds to §19 item 22):**
19. A glossy floor that ARCore does **not** track, a coffee table that it does: look over the floor for at least 3 s, then do the wall–floor taps. They are accepted, and the wall centre height is right.
20. A polished floor that ARCore **does** track, with a table reflected in it: the floor never jumps down to the reflection (wall–floor taps keep working). Note the result for `FloorEstimator.AREA_RATIO`.

## 22. PLAN v3 (2D v1), Builder B: 3D removed, Pro testing, background-free cutouts, flat Place (`docs/PLAN-v3-2D.md` W1, W7, W2, W3)

Phone: **Google Pixel 10a**. Install the v3 APK **over** the build you tested before (756b2bd or older), without uninstalling, so legacy gallery rows are tested. Room stays at **v3**: there is no database migration in this build.

**W1: 3D removed; legacy items still work**
1. Home has no "Furniture" row; Settings has no "3D Scan Lab" button; the Gallery's empty state has no "Add furniture" button.
2. **Legacy items (mandatory first):** every item saved by an earlier test build still opens, shares, deletes and **places**:
   - an M3/M4 scan (proxy box);
   - a furniture template (sofa, bed…): placed from its stored model; "Edit dimensions" is not offered;
   - a 3D scan (if you made one in the Lab): placed from its stored mesh ("Approximate 3D shape" note).
   Nothing crashes, even though v1 can no longer rebuild a template or a mesh.
3. If a legacy item's stored model can't be used, Place says "3D shape unavailable; showing the measured box" and places a plain box of its saved size (expected, not a crash).
4. The paywall's "With Pro" list shows only **No ads anywhere** and **Unlimited gallery saves**. Nothing mentions 3D scans or furniture templates (EN and HI).

**W7: testing Pro without paying**
5. **Debug build:** Settings starts with a **"Developer (debug build only)"** card. Switch **Pro (test)** on: within a second or two the line under it says "App now: Pro", the SpaceView Pro section says "Pro is active", ads disappear, and a 21st gallery item saves. Switch it off: Free again (ads may come back; the 21st save asks for Pro).
6. **Debug build, paywall:** open the paywall (Settings → SpaceView Pro, or a full gallery). Below the plans there is **"Test: unlock Pro (debug, no payment)"**. Tap it: the paywall turns into "You have Pro" without any Google Play sheet.
7. The switch survives an app restart (the fake purchase is kept on the phone, debug only). "More billing tests" still has the pending / refund / offline buttons.
8. **Release build:** Settings has **no** Developer card and the paywall has **no** test button (CI also checks the release code for them).
9. **Play licence testers (release build on the internal track):** see "Licence testers" below. A licence tester buys Pro with a test card and is **not charged**; Pro turns on. Cancel in Play → Pro ends at the (shortened) test renewal.

**Licence testers (Play Console, one-time set-up)**
- Play Console → **Settings** (left menu, account level, not inside the app) → **License testing**. Add the Gmail addresses of the testers (a Google Group works too), set **License response** to `RESPOND_NORMALLY`, and save.
- The tester's phone must be signed in to Google Play with that address, and the app must be installed **from Play** (internal or closed track; sideloaded release APKs can't buy). Test purchases show "Test card, always approves" (and slow / declining test cards for pending / declined); nothing is charged.
- Test subscriptions renew fast (a monthly plan renews about every 5 minutes, at most 6 times), so expiry and grace can be watched within an hour.
- §17 items 1–2 (licence key and restore) are run with a licence tester.

**W2: background-free cutouts** (debug or release; internet once, so Google Play services can fetch the ML Kit model)
10. **First run of the background removal:** the model comes with Google Play services (no APK download). Right after a fresh install it may not be ready yet: Review then says "Couldn't remove the background on this phone right now…" and keeps the rectangle. After a few minutes on Wi-Fi it must work. Note how long it took.
11. **Photo frame (flat):** scan a frame on a wall. Review shows the photo cut **exactly to its corners** on a checkerboard, with "Cut exactly to the measured corners." No "Undo cutout" button. Saved size and ± are as before.
12. **Chair (Other) or a carton (Box):** scan it in front of a wall. While "Removing the background…" shows, Save waits. Then the chair appears **without its background** on the checkerboard (the checkerboard shows through between the legs), with "Background removed: only the object is saved, at its measured size." The picture is the **whole measured outline** (W:H of the measured width and height) with the background transparent: the chair is never stretched (review MAJOR 5). Faint thin parts (legs) may look semi-transparent; if the mask loses a large part of the object (less than 85 % of the outline's width or height), Review keeps the rectangle and says "Couldn't find the object's edges clearly…" instead. Check with a tape: the chair's seat height in the picture matches the real chair (proportions, not just the outline).
13. **Undo cutout:** tap "Undo cutout": the full rectangle photo comes back and the note says the background is kept. "Remove background again" restores the cutout. Save stores the one shown.
14. **Hard background:** scan a box against a busy background of the same colour. If the edges are not clear (a confused mask, or one that leaves much of the outline empty), Review says "Couldn't find the object's edges clearly…" and keeps the rectangle (no crash, Save works). "Undo cutout" is then not offered (there is nothing to undo).
15. Gallery: the grid thumbnail and the item photo are the cutout (checkerboard behind a removed background). The item's size, ±, depth and the "Edit dimensions" flow are unchanged; after an edit the model is rebuilt at the new size (still a cutout).
16. Share from the item and from Saved: WhatsApp and Gmail receive the picture (a `.webp`). Note whether WhatsApp shows the transparent area as black or white (cosmetic; report it).
17. Size (review MINOR 8): Settings → Delete my data still clears everything. A **transparent** cutout has its own budget of **1 MB** (`MAX_CUTOUT_BYTES`: its texture must be an RGBA PNG); a flat (frame/TV) or undone cutout stays ≤ 0.5 MB. Check with `adb shell run-as com.spaceview.app.debug du -b files/gallery/<id>` and record the sizes and, for the chair, the texture size (expected ≥ 640–768 px for a real photo; JVM measurements are in the HANDOFF). If even the smallest PNG did not fit, the item is saved as the opaque rectangle (never as a plain panel).
18. **Save time (gate W1): a cutout Save takes ≤ 2 s on the Pixel 10a.** Time it from tapping Save on the chair's Review to the Saved screen (stopwatch), for 3 different objects. On a debug build the exact numbers are in logcat: `adb logcat -s SpaceViewCutout` prints one line per cutout save (`PNG <n> px (alpha)` or `JPEG (opaque)`, GLB and total bytes, `encode … ms`, `write … ms`, `save … ms`). Record them; over 2 s is a finding.
19. Hindi: every new Review note and button is in Hindi; Help → "Scans and cutouts" and the privacy text mention on-device background removal.

**W3: flat Place in room**
20. **Chair (Floor):** Place opens on Floor. The chair stands **upright with its bottom edge on the floor** (no gap, not sunk), at true size (check with a tape against the real chair next to it). Move, rotate (it turns about the vertical) and nudge work as before.
21. **Frame (Wall):** Place opens on Wall. The frame **hangs flat on the wall** (not standing out from it), centred on the reticle; the height slider and "Eye level" work; a quarter turn switches portrait ↔ landscape.
22. **No halo:** look closely at the chair's edges against a light floor and against a dark one: no black (or white) outline around the object, and the background really is gone (you see the room through the gaps).
23. Both sides: walk behind a placed cutout: its back shows the same picture (mirrored), never an invisible or black quad.
24. **Check the fit:** measure a gap or a floor rectangle. The verdict uses the item's **measured** width × height on a wall and width × depth on the floor (a measured depth counts; a frame's preset depth says "Using your typed size"). It never compares against a zero depth.
25. A legacy proxy box (M3/M4 scan) still places as a 3D box, and a legacy template / mesh as in item 2.

---

## 23. PLAN v3 (2D v1), Builder A: height by vertical plane, surface finding, Distance up to 500 m (`docs/PLAN-v3-2D.md` W5, W6, W4)

Phone: Google Pixel 10a. **"Use depth" is now ON by default**, so first check it is on in Settings (W6 finding: it used to be off, which slowed plain surfaces).

1. **Surface, textured floor:** from a fresh Measure open, read the debug HUD line (`dbg track · hit · lock · now · depth · rep`). Targets: hit ≤ 2 s, lock ≤ 2.5 s. Do 5 runs with depth on and 5 with it off, and record the times, the first hit source and `rep`.
2. **Surface, plain painted wall:** target hit ≤ 4 s. Expect a depth point first when depth is on; with it off, expect a slow hit or none.
3. **Height, tilt:** a 2 m door from 3 m away, with the base exactly below the top (the hint now says so).
   - Tilting up to 60°, the point stays on the door's vertical line and the result is inside its ±.
   - The ± grows as you tilt. It also carries a 2 cm "base not exactly below the top" allowance (`HeightAim.BASE_OFFSET_SIGMA_M`).
   - Past about 75°, the "Too steep" hint shows.
   - Put the base 10 cm in front of the door line and tilt to 60°: the height is off by about 17 cm. This is expected (outside the 2 cm allowance); note it.
4. **Height, far base:**
   - About 7–8 m away on a level floor, with nothing under the crosshair for 5 frames, the base lands on the floor.
   - The result card says "The base is on the floor far away: this assumes a level floor."
   - Record the distance at which the far base is refused (about 8–9 m at hand height with the 0.3° slope allowance).
5. **Scan box height:**
   - On a low box, the top-face hit is used and H matches a tape measure.
   - On a wardrobe taller than eye level, aim at the top edge of the face you see, anywhere along it: the point lands on that face's top edge and H is inside its ±.
   - Aim at the top edge 10 cm off centre, and from a diagonal view at the far vertical edge: H still matches. These cases used the wrong corner before the fix.
6. **Distance, Near:** a 5 m tape check is inside the ±, with the badge "AR point".
7. **Distance, Ground:**
   - At 6 m and 8 m on level paving, the reading is inside the ± and shows the badges "Ground" and "Assumes the point is on level ground".
   - **Limit:** one sight on the ground reaches about 8.5 m with the phone at hand height (more from a higher camera).
   - At 12 m on open paving, it says "Too far for one sight. Use 2 sights." This holds even when depth finds the paving.
   - Aim at the foot of a wall or a car about 9 m away: it says "Something is in the way before the ground…", never a Ground number.
   - Aim at a kerb or car wheel (blocked), then lower the crosshair slowly onto open paving in front: Ground comes back without jerking the phone.
   - Aim at a box top about 3 m away: it shows "AR point", never "Ground".
   - **Slope check:** on paving with a known 1–2% fall, record the error against a tape. If it is outside the ±, raise `RangeErrorModel.LEVEL_GROUND_SLOPE_DEG` towards 0.4°.
8. **Distance, 2-sight:** at 100 m and 300 m, check against a map, GPS or a known distance.
   - The ± covers the truth.
   - The walk asked for:
     - about 12 m at 100 m;
     - 30 m at 300 m, shown within the first few metres of walking (once the two sight lines are about 0.5° apart), not only at the end.
   - The walked distance updates while you walk. A preview number appears once a sight 2 there would give one.
   - Taking sight 2 before the asked walk is done on a far target says "Not accurate enough yet. Walk further sideways…". It never says "Out of range" while you are under 30 m.
   - **Honest limit as built:** a number is shown only when its whole ±95 interval is within 500 m. With the full 30 m walk and the 0.24° aim error, that is about 380–410 m. Beyond that, after the full walk, it shows "Out of range: may be over 500 m".
9. **Refusals:**
   - Cover the camera between sights → "tracking lost", and no retake is offered.
   - Get a refusal, cover the camera while the result is on screen, then uncover it → Retake is no longer offered.
   - Leave the app with a result on screen and come back → Retake is not offered.
   - Aim sight 2 at a different object → "different things".
   - Walk 1 m on a 200 m target → "near-parallel" (sometimes "Not accurate enough yet": the measured angle is noisy).
10. **Out of range:**
    - A point at 600 m, after the full 30 m walk, shows "Out of range: may be over 500 m". It is never a number (fewer than 1 in 200 tries in the Monte-Carlo test).
    - A target at exactly 500 m also usually shows "Out of range".
11. **TalkBack:** Hold and every 2-sight result are spoken once, in words. The live value is spoken once it settles.
12. **Ads:** no ad appears on the Distance screen or when leaving it.
    - On a first launch (or with consent still pending), tap "How far?" straight after Home: no consent form appears over the camera.
13. **Hindi:** in the Hindi locale, every Distance string shows in Hindi.
14. **Imperial units:** decimal feet, never with more digits than the ± supports.

## Values to tune from results
Aim error 2 px, motion gates, RANSAC limits, Good/Fair/Rough thresholds, device-tier ± floors (1 cm / 5 mm), calibration acceptance.
