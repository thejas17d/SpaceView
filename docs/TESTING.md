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

## Values to tune from results
Aim error 2 px, motion gates, RANSAC limits, Good/Fair/Rough thresholds, device-tier ± floors (1 cm / 5 mm), calibration acceptance.
