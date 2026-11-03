# SpaceView: UI/UX Research

**Status:** Planning-phase research. No app code yet.
**Prepared:** 2 Nov 2026, by the UI/UX research agent
**Scope:** Competitor UX, platform AR guidelines, information architecture, communicating accuracy, ad placement, accessibility and India-market specifics, visual direction.

> How to read this: every **Recommendation** is our proposal. Every claim about a competitor, a platform or a policy is cited (numbers in [brackets] point to the Sources list at the end). Where a claim comes only from a vendor or a single anecdotal review, it is marked *(vendor claim)* or *(anecdotal)*. Re-check policy text against the live pages before launch, because AdMob and Play policies change.

---

## 0. TL;DR: the 12 decisions that matter most

1. **Be honest about precision.** On mid-range Android phones without depth sensors, phone AR gives an *estimate*. Every measurement shows a **± tolerance** and a **3-level quality badge** (Good / Fair / Rough), and we round the number to match the tolerance (we never show "42.37 cm ± 1 cm"). Every serious competitor hedges: Apple calls its results approximate, CamToPlan says "accuracy not guaranteed", and Magicplan says accuracy "depends" [7][14][13].
2. **Use a centre-fixed crosshair, not tap-to-place points**, for measuring. You move the phone and press a big bottom-centre "+" button. This is the Apple Measure pattern. It works one-handed and avoids fingers covering the target.
3. **Offer reference-card calibration** (any bank card or PVC Aadhaar card is the ID-1 size, 85.60 × 53.98 mm; an A4 sheet also works) as an optional "Boost accuracy" step. This is our main lever for "high precision" on phones without LiDAR or ToF sensors.
4. **The scan → save flow captures a measured box plus a straightened front photo** (a "card" or "box" proxy), not a full 3D mesh, in v1 on Android. This is fast, small and works offline. Full photogrammetry can be a later, cloud-assisted mode (Polycam on Android is photo-mode only [10]).
5. **Placing an object always uses true scale, with scaling locked.** Apple's HIG says that letting shoppers scale furniture "doesn't help them visualize" [3]. Show a "1:1 True size" lock chip.
6. **Give a fit verdict that includes uncertainty**: "Fits, 6 cm to spare", "Tight: check with a tape", or "Too big by 4 cm". This verdict is the core value of the product.
7. **Wall placement needs a fallback.** Indian homes often have plain, single-colour painted walls, and Google warns that such vertical surfaces often fail detection [2]. Offer "mark where the wall meets the floor" so we can build the wall plane from the floor plane.
8. **Never show an ad inside a camera or AR view.** The interstitial appears only *after* the "Saved" result screen, when the user taps Done. It never appears when the user taps "Place in room". The first scan in a session is ad-free, and there is a time-based cap (AdMob allows at most one interstitial per two user actions [5]).
9. **Banners appear only on list screens without a camera** (Gallery, History, Settings), never next to the shutter or navigation controls. AdMob names "gallery image" and "next" buttons as risky neighbours [15]. Partner catalogue pages carry **no AdMob ads**.
10. **Share to WhatsApp first**: an annotated spec image ("Frame 45.7 × 61.0 cm (18 × 24 in)") plus a link. That is how measurements actually travel in India.
11. **Units are context-aware**: feet and inches for rooms and people, cm or inches for objects, and inches on the diagonal for TVs and monitors (Indian retail lists TVs as "108 cm (43 inch)"). Always show a secondary unit.
12. **Visual language is a precision instrument**: drafting-style dimension lines, tabular numerals, a graphite and paper palette, and one high-visibility signal colour. No gradients, sparkles or "AI-powered" badges.

---

## 1. Competitor UX study

### 1.1 Summary table

| App | Platform / tech | What works | Common complaints / weaknesses | How it communicates accuracy |
|---|---|---|---|---|
| **Google Measure** | Android, ARCore (formerly Tango) | It was the reference ARCore measuring UI. | **Discontinued in June 2021.** Google said it could "no longer support ongoing maintenance". Reviews cited instability and inaccurate measurements [1]. | Did not communicate it, which is part of why it failed. |
| **Apple Measure** | iOS, ARKit, LiDAR on Pro models | Centre crosshair plus a "+" button. Auto-detects rectangles and draws a box. On LiDAR devices, **guide lines snap to straight edges**. Tapping a measurement shows detail such as angle and elevation [7]. Translucent screen-space controls (cited in the HIG) [3]. | Early non-LiDAR versions were inconsistent, and repeated measurements disagreed. Weak for small objects and glossy surfaces. Fine for estimates, not for precise work [6]. | Apple's guidance describes results as approximate. No ± is shown in the UI [6]. |
| **AR Ruler** (several clones) | Android/iOS, ARCore/ARKit | Many modes (line, path, angle, area, volume, height). | Ads are the top complaint: "Ads just blast out and measurements are way off", and ads returned after paying (complaints against *AR Ruler / AR Ruler Cam*) [12] *(anecdotal)*. | Usually none. |
| **Moasure** | Phone/device motion (IMU), not camera | **Best-in-class accuracy communication**: a Start/Finish **% error** after each measurement, a "stay in the green zone" live indicator, and **Moasure Coach**, which points out *where* accuracy dropped and why (movement, placement, timing) [8]. | Needs technique (pausing, holding still). Not all phones are supported, and the app checks sensors on install [9]. | Published per-mode tolerances (for example ±0.3% linear) converted into absolute values [8]. |
| **CamToPlan** | iOS/Android AR tape | Simple tape plus floor-plan output. Premium removes ads. | Floor detection fails on some tiles ("Terrible at picking up the floor") [12] *(anecdotal)*. | App Store copy says outright that **accuracy is not guaranteed** [12]. |
| **IKEA Place / Kreativ** | iOS (LiDAR-enhanced), Android | True-scale placement. Kreativ can erase existing furniture and design the room [16]. | **Drift, floating and wrong scale in low light** ("forcing you to rescan your room") [17]. Kreativ scanning is cumbersome (minutes to capture and upload) [16]. Saved scans are hard to find [18]. | Marketing claims of "98% accuracy" with no independent test [17] *(vendor claim)*. |
| **Amazon View in Your Room / AR View** | iOS/Android | One-tap entry from the product page. Shown "in scale" [19]. | Shiny countertops are not recognised as surfaces. Originally one product at a time [19] *(anecdotal)*. | Claims to be to scale. No tolerance shown. |
| **Magicplan** | iOS/Android, room scan | Floor plans, plus pairing with a **Bluetooth laser** for exact figures [13]. | Corner method requires standing in one spot. Corners are missed when furniture is in the way. Poor in the dark [20] *(anecdotal)*. One third-party site claims the Android AR scan was removed in late 2024, which we could not verify [20]. | Says accuracy "depends", with lighting "the most crucial" factor. "100%" is only claimed with a laser [13]. |
| **Polycam** | iOS LiDAR plus photo mode; Android photo-only | Fast LiDAR mesh. Auto-capture that tells you to slow down [21][22]. | Pricing complaints (Pro at roughly $199.99/yr per a 2026 review) [21]. An older review noted no "painted" coverage display [22]. | Shown implicitly through mesh quality. |
| **Canvas** (Occipital, reportedly renamed Twindo in 2026) | iOS LiDAR only | Scan-to-CAD with human-assisted drafting [23]. | LiDAR only. Paid per square foot. | **States "~1–2%" tolerance** and offers to "submit manual critical dimensions" for higher accuracy [23] *(vendor claim)*. |

**Indian context:** Flipkart Camera (AR "view in your space"), Lenskart 3D try-on and Pepperfry or Urban Ladder room visualisers have taught Indian shoppers the "see it in your room" idea [24]. We found **no AR feature from Croma**, which is an opening for SpaceView as a partner.

### 1.2 Patterns to copy

- **Apple Measure's crosshair plus "+" button.** It works one-handed, keeps the target visible, and the reticle stays the same size wherever you aim [7].
- **Snapping to edges and corners** with a visible "snap" state (Apple guides) [7]. On Android without depth, we can snap to plane boundaries, to existing points, and to rectangles detected by on-device edge detection.
- **Moasure's after-measurement coaching.** It shows what went wrong and how to fix it, instead of a bare number [8].
- **Canvas and Magicplan's escape hatch**: "for exact numbers, enter or verify critical dimensions manually" [13][23]. We should make manual override a first-class feature, not a failure.
- **Amazon's one-tap "View in your room" entry** from product detail. Our gallery item pages need the same.

### 1.3 Pitfalls to avoid

- Unqualified precision claims ("98% accurate") that users then disprove with a tape. This destroys trust and leads to 1-star reviews.
- Low-light drift with no warning. IKEA Place users had to discover it themselves [17]. We should warn **before** placement when light is low.
- Ads that interrupt measuring. This is the most common AR Ruler complaint [12].
- Saved items buried in deep folders [18]. The gallery needs search, filters and thumbnails that show the dimensions.
- Heavy, slow scanning (Kreativ's multi-minute capture and upload) [16]. The scan should take **under 30 seconds** and save on the device.

---

## 2. Platform AR guidelines (Apple HIG and Google ARCore)

### 2.1 Onboarding and coaching ("move your phone")

| Guidance | Source | SpaceView application |
|---|---|---|
| Use a coaching view during initialisation and **relocalisation**, and **hide unrelated UI** while coaching | Apple HIG [3] | While AR starts, hide mode tabs, gallery and ads, leaving only the coaching animation, Cancel and Help. |
| A custom coaching view is fine, using the system view as reference | Apple HIG [3] | Build one Compose coaching component (phone-sweep animation) and reuse it on iOS. |
| Give clear feedback, encourage the user to move the device, and show how to interact | ARCore runtime docs [4] | A three-step micro-onboarding shown the first time only and replayable from Help. |
| Ask users to **move slowly**. Rapid movement blurs the image and loses tracking | ARCore [4] | Detect high angular velocity and show "Slow down a little". |
| **Avoid haptics during an ARCore session**, because vibration disturbs pose estimation | ARCore [4] | **Android:** use a soft click sound plus a visual pulse instead of vibration. **iOS:** Apple *encourages* haptics for contact feedback [3], so enable them there. This is a deliberate platform difference. |
| Show how to find a surface and **confirm detection instantly**, then say what to do next | ARCore content placement [2] | When the plane is found, the dotted grid fades in under the reticle, followed by "Aim at the first corner, then tap +". |
| Vertical surfaces are hard (reflective, single colour) | ARCore [2] | Wall fallback flow (see §3.5). |
| Warn about lighting that is too dim or too bright | ARCore [2] | A light chip in the top bar ("Low light: turn on a light"). |
| Use friendly language with no jargon: "Try moving your phone more slowly", not "Excessive motion detected" | Apple HIG table [3] | Copy table in §2.4. Never say "plane", "tracking", "features" or "ARCore". |

**Recommended first-run sequence (3 screens maximum, skippable):**
1. "Point at the object. Keep it in good light." (illustration of the phone held at chest height)
2. "Move your phone slowly from side to side until you see the dots." (looping animation)
3. "For best accuracy, keep about an arm's length away." (illustration of distance)
Then ask for camera permission with a reason ("SpaceView uses your camera only to measure. Nothing is uploaded unless you share."). Ask in context, at the moment the user first taps Measure, not at app launch.

### 2.2 Surface detection feedback and reticle design

**States of the reticle (crosshair centred on screen):**

| State | Visual | Text chip | Sound (Android) / haptic (iOS) |
|---|---|---|---|
| Searching | Hollow ring, dashed, slowly rotating, projected flat in screen space | "Move slowly to find a surface" | none |
| Surface found | Solid ring lying **on the plane** (perspective-correct, aligned with the plane normal; Apple says to align the indicator with the detected plane [3]), with a faint dot grid on that plane only | "Aim at the first point" | one soft tick |
| Snapped (to an edge, corner, existing point or rectangle) | Ring shrinks to a filled dot and corner brackets appear | "Corner" / "Edge" label | tick |
| Measuring (after the first point) | Live dimension line from point A to the reticle, with a live number and ± | — | — |
| Can't place here (unsupported surface, too far) | Grey ring with a slash | "Too far: move closer" | none |

Design rules:
- **Highlight only the surface being pointed at**, with distinct planes visually separated (ARCore [2]).
- **Two-tone reticle** (white core with a 1.5 dp dark outline) so it stays visible on both white walls and dark floors.
- **Loupe:** when a point is being placed or dragged, show a 2× magnifier bubble above the thumb so the finger never hides the target.
- **Fixed screen size.** The reticle does not shrink with distance. Instead, a small distance readout ("0.8 m") appears beside it, because distance drives accuracy.

### 2.3 Tracking-loss and interruption states

ARCore reports tracking failure reasons (insufficient light, excessive motion, insufficient features, camera unavailable, bad state). Map each reason to friendly copy and a recovery path:

| Condition | UI behaviour | Copy (EN) |
|---|---|---|
| Initialising (> 3 s) | Coaching overlay | "Move your phone slowly side to side." |
| Insufficient light | Light chip; measurements turn **Rough** | "Too dark. Turn on a light or move closer to a window." |
| Excessive motion | Pulse the reticle | "Move your phone more slowly." |
| Insufficient features (plain wall, glossy tile) | Hint card | "Try pointing at something with texture, like a switchboard, the edge of a rug or a frame." |
| Tracking lost, measurement in progress | **Freeze the existing points and hide them** to avoid flicker (Apple [3]); show the relocalisation coach | "Point back at where you were measuring." |
| Interruption (call, app switch) | Relocalise; offer **Start over** (Apple: let people cancel relocalisation [3]) | "Pick up where you left off, or start over." |
| Relocalisation fails (> 10 s) | Offer to save what is already measured as a draft | "Couldn't find your spot. Save the measurement taken so far?" |
| Device not supported | Hide AR modes entirely; offer manual entry and photo-plus-card mode (Apple: don't show an error, simply avoid offering the feature [3]) | — |

### 2.4 Copy guide (do / don't)

| Do | Don't |
|---|---|
| "Can't find a surface. Try moving to the side." | "Unable to detect plane." |
| "Tap + to set the first corner." | "Tap to anchor point." |
| "Turn on more lights and move around." | "Insufficient features." |
| "Fair accuracy (± 1 cm). Move closer to improve." | "Confidence: 0.62" |

(The first three follow Apple's HIG table [3].)

### 2.5 Comfort and safety

- **Don't make people walk backwards.** This comes from Google's AR design guidelines, as summarised by a reviewer [25]. In practice, our flows always guide the user *forward* or *sideways* ("Step closer", "Move to the side"), never "step back". If an object needs more distance, we say "Move the phone back slightly", not "step back".
- **Comfort:** holding a phone up for a long time is fatiguing [3]. Measuring sessions should be short. Allow **Freeze frame**: hold the phone up, tap the snowflake, then lower the phone and adjust points on the frozen image (the camera pose is kept). This is a big win for comfort and accuracy.
- **Placement range:** ARCore recommends a default maximum placement distance, with "center stage" as the comfortable zone [2]. Place new objects 1–1.5 m in front of the user on the floor or wall.
- **Safety notice** on first AR use: "Watch your surroundings: stairs, pets, traffic." Shown once, not modal on every launch.
- **Thermal and battery:** pause the AR session and camera whenever a sheet or dialog covers more than half the screen, and on any non-AR screen.

### 2.6 Object interaction during placement (from the Apple HIG [3])
- One-finger drag to move along the surface, two-finger twist to rotate. **No pinch-scale** (true scale is locked).
- Respond to gestures *near* the object, not only on it.
- Keep the object attached to the surface while dragging. Never let it jump or vanish.
- For precision, show **nudge arrows** (±1 cm per tap, long-press to repeat) and a **rotate 90°** button in screen space, reachable by thumb. AR touch gestures are imprecise, and nudge controls also help one-handed use and accessibility.

---

## 3. Information architecture and screen list

### 3.1 Top-level navigation (bottom bar, 4 tabs)

```
┌───────────┬───────────┬───────────┬───────────┐
│  Measure  │  Gallery  │   Shop    │    You    │
│ (default) │ (AR items)│ (partners)│ (settings)│
└───────────┴───────────┴───────────┴───────────┘
```
- **Measure** opens a launcher (not straight into the camera): this saves battery and lets the user pick a mode. A large primary button, "Scan an object", sits above the secondary modes.
- A **"Place in my room"** floating action is reachable from Gallery and from any item detail page.
- The bottom bar is **hidden in all camera/AR screens**, which are full-bleed with their own controls (Apple: "use the entire display" [3]).

### 3.2 Screen inventory

| # | Screen | Purpose | Ads? |
|---|---|---|---|
| S0 | Splash / ARCore check | Check support and install Google Play Services for AR if needed (show the download size and a Wi-Fi hint) | No (AdMob forbids interstitials on app load [5]) |
| S1 | Onboarding (3 cards) + language picker | First run only; language first (Hindi/English, then others) | No |
| S2 | **Measure home** | Big "Scan an object"; mode grid: Line, Height, Rectangle/Frame, Box (W×D×H), Area (floor), Path; recent measurements strip | Banner allowed at the bottom, **only** if it is spaced away from the mode buttons, so preferably no banner here (see §5) |
| S3 | **AR Measure (camera)** | Crosshair, live line, ± chip, quality badge, undo, freeze, unit toggle | **Never** |
| S4 | **Scan Object flow (camera)** | Guided capture: footprint, height, front photo | **Never** |
| S5 | Calibrate with card (optional step in S3/S4) | Lay a bank or Aadhaar card or an A4 sheet flat and fit its outline | **Never** |
| S6 | **Review and edit dimensions** | Dimension card, ± values, per-dimension edit, re-measure one side, rename, category, tags | No (part of the active task) |
| S7 | **Saved result card** | "Saved to Gallery" with thumbnail and dimensions; actions: Place in my room / Share / Done | No ad *on* the screen; the interstitial may show **after Done** (see §5) |
| S8 | **Gallery** | Grid/list of saved items (mine plus partner items saved by me); search, filter by category or size, sort by date or size | Banner (anchored adaptive), placed carefully |
| S9 | Item detail | Large photo, spec block (W/H/D, diagonal, ±, method, date, place captured), notes, provenance badge ("Scanned by you" / "Retailer verified") | Banner at the bottom of the scroll is acceptable; **none** on partner items |
| S10 | **Place in my room (AR)** | True-scale placement, fit verdict, clearances, nudge/rotate, multi-item compare, snapshot | **Never** |
| S11 | Share sheet | Annotated image, PDF spec card, link (web 3D viewer), WhatsApp first | No |
| S12 | **Shop / Retailer catalogue** | Partner storefronts (e.g. Croma, Reliance Digital, furniture brands) → category → product → "View in your room" / "Save to Gallery" / "Buy at partner" | **No AdMob** (brand safety; partners are the monetisation) |
| S13 | History | Quick, unsaved measurements (the last 50) with one-tap "Save to Gallery" | Banner OK |
| S14 | You / Settings | Units, display precision, language, sound, accessibility, calibration profile, data saver, ad personalisation consent, **Remove ads** (in-app purchase), privacy, help | Banner OK (bottom, away from toggles) or none |
| S15 | Help & accuracy guide | "How accurate is SpaceView?", tips, troubleshooting | No |

### 3.3 Core flow: Scan → Save → Place

**A. Scan (S4): about 20–40 s, 5 steps with a step indicator at the top ("2 of 4")**

1. **Pick the type** (one tap, which sets the capture recipe and the defaults):
   *Flat on a wall* (frame, mirror, TV, poster) · *Box-shaped* (cabinet, appliance, box) · *Round* (table, plant pot) · *Something else*.
   Wording note: the café-frame use case is "Flat on a wall".
2. **Find the surface.** Coaching overlay, then the plane is found. For "Flat on a wall", the user aims at the wall; if the wall isn't found within 5 s, offer **"Use the wall–floor line instead"** (§3.5).
3. **Set the outline.**
   - *Flat:* place two diagonal corners with the crosshair (the rectangle snaps to detected edges if available). The live W × H appears with ± and the diagonal (for TVs and monitors).
   - *Box:* tap the 3 base corners on the floor or table (giving W and D), then raise the height handle until it touches the top. A wireframe box appears, with dimension lines on each edge.
   - *Round:* tap the centre, then the edge (giving the diameter), then the height.
4. **Capture the appearance.** "Step to face it straight on, then hold still": an auto-capture fires when the phone is roughly parallel (live tilt guide) and steady. For box items, an optional "Add side photo". We crop and straighten the photo using the measured outline, so the texture matches the dimensions exactly.
5. **Accuracy check (inline, not a separate screen).** Show the quality badge. If the result is Fair or Rough, show **one** best tip with an action: "Measure again from 50 cm. Usually improves to ± 0.5 cm" [Re-measure] [Calibrate with card] [Keep as is].

**B. Review (S6)**
- Dimension card: `W 45.5 cm ± 0.5 · H 61.0 cm ± 0.5 · Diagonal 76.1 cm (30.0 in)`
- Each value is tappable: **Edit number** (keypad with a unit switch), **Re-measure this side** (back into AR for one edge only), or **Lock as exact** (for example, "the shop label says 45 × 60").
- Auto-suggested name ("Wooden frame"; on-device image labelling can propose a category, but the user confirms it). An optional note and **where** ("Blue Tokai, Indiranagar", from manual entry; never auto-attach location without opt-in).
- Primary button: **Save to Gallery**.

**C. Saved (S7)**
- Confirmation (motion: the thumbnail flies into the Gallery tab), with three actions:
  **[Place in my room]** (primary) · **[Share]** · **[Done]**
- Ad rule: an interstitial may show *only* when leaving via **Done** or the system Back gesture (end of a content segment), and never when the user chooses Place or Share (see §5).

**D. Place (S10)**
1. Choose a destination type based on the item type: wall item → find wall; floor item → find floor; tabletop item → find table.
2. A ghost footprint at true size follows the crosshair (with a soft contact shadow so it reads as grounded [3]). A "1:1 True size" chip is always visible.
3. Tap to drop it. The object is integrated immediately and refined later (Apple: don't wait for perfect data [3]).
4. **Fit tools:** clearance callouts to nearby detected planes or edges ("12 cm to the cupboard", "1.42 m above the floor"); for frames, the centre height shown against an "eye-level guide" option.
5. **Fit verdict**, when the user measures a gap (e.g. wall space between two windows): **Fits ✓ (6 cm spare)** / **Tight ⚠ (within the margin of error: confirm with a tape)** / **Too big ✕ (by 4 cm)**.
6. Snapshot button: the photo is saved with dimension overlays and can be shared straight away.
7. Compare: add a second gallery item (e.g. a Croma TV next to your own scanned cabinet).

### 3.4 Measure modes (S3)
Line (point to point) · Height (floor to point, using the floor plane plus a vertical guide) · Rectangle/Frame (auto-rectangle) · Box · Floor area (polygon, in sq ft / m²) · Path (multi-segment total). Defer angle, volume and level until after v1. AR Ruler-style mode overload contributes to confusion; a focused set is better.

### 3.5 Wall fallback (India-specific importance)
Plain, single-colour painted walls are common in Indian homes and rentals, and these are exactly the surfaces ARCore struggles with [2].
Fallback: (1) detect the floor (usually textured: tiles, rug); (2) the user aims the crosshair at the line where the wall meets the floor and taps two points along it; (3) we build a vertical wall plane through that line; (4) the frame can now be placed on this virtual wall. Copy: "Can't see the wall clearly. Tap two points where the wall meets the floor."

### 3.6 Retailer catalogue (S12) and the provenance model
- Every gallery item carries a **provenance badge**:
  - `Scanned by you · ± 0.5 cm` (user scan, with tolerance)
  - `Retailer dimensions · Croma` (catalogue data, exact as published; shown with "as listed by retailer")
  - `Edited by you` (user overrode measured values)
- Partner pages carry a clear **"Partner"** label (sponsored disclosure), and a "View in your room" button on every product. Use a consistent badge in the same corner of every product thumbnail (Apple's AR badging guidance [3]; on Android, use our own badge, not Apple's ARKit glyph).
- Retailer 3D models: show the download size before fetching ("3.1 MB"), offer "Download on Wi-Fi only", and cache saved items for offline use.
- "Buy at Croma" deep-links out, with a note that the price may change.

---

## 4. Communicating accuracy and uncertainty

### 4.1 Principles
1. **Every number carries its tolerance.** Format: `45.5 cm ± 0.5`. Show ± in the same unit as the value.
2. **Displayed precision follows the tolerance.** If ± ≥ 1 cm, show whole cm; if ± < 1 cm, show 1 decimal place; never show mm unless ± ≤ 2 mm (only with card calibration at close range). In inches, round to 1/4" or 1/8" according to the tolerance. Showing false precision is the fastest way to lose trust.
3. **Use words and shape, not only colour.** Error bars are poorly understood by non-experts [26], so pair a plain-language badge with the ±:

| Badge | Icon shape | Rule of thumb | Copy |
|---|---|---|---|
| **Good** | Filled circle ● | ± ≤ 0.5 cm, or ≤ 1% | "Good: within about half a centimetre" |
| **Fair** | Half circle ◐ | ± ≤ 2 cm, or ≤ 3% | "Fair: fine for planning, check before buying" |
| **Rough** | Empty circle ○ | anything worse | "Rough estimate: move closer or add light" |

(The thresholds are placeholders for the engineering team to calibrate against our own bench tests across target devices.)
4. **Show the uncertainty in the AR view itself**: render the dimension line as a thin sleeve (tolerance band) whose thickness at the end caps is proportional to ±. It shows imprecision visually without any text.
5. **Explain the method on demand.** Tap the badge for "How we measured: depth estimate, 3 samples, 0.7 m away, card-calibrated". This transparency is the opposite of "AI magic".
6. **Coach after the fact, like Moasure** [8]: name the single biggest cause ("You were 1.8 m away; under 1 m is best") with a one-tap fix.

### 4.2 Inputs to the uncertainty estimate (for engineering)
Distance to the target, tracking state and its recent history, plane confidence or plane age, whether depth is available (Depth API support varies by device and must be checked at runtime [27]), angle of incidence, number of repeat samples, card calibration on or off, and light level. Expose the model output as one ± per dimension.

### 4.3 Ways to improve accuracy (offered in this order)
1. **Move closer** (target: 0.5–1 m).
2. **Re-measure and average**: "Measure again from a different angle. We'll combine both." Show the spread of the samples.
3. **Calibrate with a card**: place a bank card, PVC Aadhaar card or other ID-1 card (85.60 × 53.98 mm), or an A4 sheet, flat near the object; the app finds its outline and computes a scale correction. Badge: "Card-calibrated".
4. **Freeze and refine**: freeze the frame, then drag the endpoints with the loupe.
5. **Enter a known value**: "Know one side exactly? Enter it." For photo-based captures, the other sides can rescale proportionally (shown as "Scaled from your 45 cm").

### 4.4 User correction UX
- Tap any dimension, in AR or on the review card, to open the **Edit sheet**: numeric field (with the unit switch), **Re-measure this side**, **Reset to measured**.
- Edited values show a pencil marker and the provenance "Edited by you"; the original measured value is kept in item history.
- **Fit verdicts use the edited value** but warn if it differs from the measured value by more than 3× the tolerance ("Your value differs from what we measured. Double-check?").
- Undo is always one tap away in AR (bottom-left, in thumb reach), plus "Clear all".

### 4.5 Marketing and store copy (to stay credible)
Say "Measure in seconds, with an accuracy rating on every result" rather than "precise to the millimetre". Publish an **accuracy page** (S15) with honest numbers from our own tests, by device class.

---

## 5. Ad placement UX

### 5.1 Policy constraints that apply to SpaceView
- **Interstitials only at natural breaks between content**, never during an active task; **not on app load or exit**; **at most one interstitial per two user actions**; never back-to-back; never on every click; must not block core content; **pre-load** to avoid an ad appearing late, after the next screen loads [5][11].
- Play's disruptive-ads policy: "unexpected" means mid-task, or the user tapped a button **expecting to start an activity** and got an ad. Placement *after* a results screen (the "score screen") is expected [28]. Full-screen interstitials that can't be closed after 15 s are prohibited [29].
- **Banners:** never next to interactive buttons ("next", menu bars, gallery images, gameplay); no floating or overlapping banners; accidental clicks lead to revenue deductions and suspension [15].
- **No ads on dead-end or no-content screens** (thank-you, exit, error, loading), or in apps or screens where the user is looking *away* from the screen (the flashlight example) [30]. An AR viewfinder, where the user's attention is on the world, is closely analogous, which is another reason to keep ads out of camera views.
- **Frequency capping** is available per ad unit and app-wide (e.g. "2 impressions per 30 minutes") [31].

### 5.2 Interstitial placement ("one per scan" without annoyance)

```
Scan (AR) ──> Review ──> Save ──> Saved card
                                   ├── [Place in my room] ──> AR (NO AD)
                                   ├── [Share] ──> share sheet (NO AD)
                                   └── [Done] / Back ──> [INTERSTITIAL if eligible] ──> Gallery/Home
```

Eligibility rules (all must be true):
1. The user completed and **saved** a scan (a whole content segment is finished). Abandoned scans never trigger an ad.
2. It is **not the user's first scan of the session**, and not their first-ever scan (a grace period for first impressions and onboarding).
3. At least **3 minutes since the last interstitial** (client-side), plus an AdMob unit cap of about **4 per hour** as a backstop [31].
4. The ad is already **pre-loaded**. If it is not ready, skip it: never show a spinner and then an ad.
5. The user is not in "Place", has not just tapped Share, and has no system dialog open.
6. **Deferred placement:** if the user taps "Place in my room", the owed ad is *carried over* to the next natural exit (closing the AR placement screen back to the Gallery), and only once.

Plus:
- Pause and release the AR session and camera **before** the ad shows (Firebase: pause the action during an interstitial [11]), and give the ad the full screen.
- After the ad closes, land on the expected screen (Gallery, with the new item highlighted), so the ad feels like a pause, not a detour.
- **Rewarded option:** "Watch an ad to unlock 24 h ad-free" or "...to unlock HD export". Opt-in ads are explicitly not "unexpected" [28].
- **Remove ads** in-app purchase (one-time, priced for India, e.g. ₹99–₹199; validate pricing separately), visible in You → Settings and in a soft prompt after about the 5th interstitial. Never shown during a task.

### 5.3 Banner placement

| Screen | Banner? | Position and spacing |
|---|---|---|
| AR Measure, Scan, Calibrate, Place | **Never** | — |
| Review / Edit dimensions | No | Active task (like "filling out a form" [5]) |
| Saved card | No | Effectively a "thank-you" screen [30] |
| Measure home (launcher) | Optional, preferably none | If used: an *inline* adaptive banner between "Recent" and the footer, at least 16 dp from any button; never between the mode grid and the nav bar |
| Gallery grid | Yes | **Inline**: a full-width banner row after every 12 items, with visible separation and an "Ad" label (AdMob flags placements next to *gallery images* [15], so don't put it in a grid cell next to a thumbnail; use a full-width row with gutters) **or** an anchored adaptive banner at the top below the search bar. Avoid sandwiching the banner between content and the bottom nav [15]. |
| Item detail (own items) | Yes | At the end of the scroll content, below the spec block, separated by a divider |
| Partner catalogue and partner items | **No AdMob** | Partners would object to competitor ads (e.g. another electronics retailer) next to their products; monetise these through partnership deals. If banners are ever allowed, use AdMob's blocking controls for competing advertisers and categories. |
| History, Settings | Yes | Anchored bottom, with a 12 dp gap above the nav bar |

### 5.4 Ad UX guardrails
- Reserve the banner's space before the ad loads, so the layout never jumps (no click traps).
- Respect data saver: on "Data saver" or a metered network with low balance, prefer text or static creatives where mediation allows. At minimum, don't preload video interstitials on 2G/3G-class connections.
- Ad personalisation consent through Google's User Messaging Platform where required. Check India's DPDP Act 2023 and its Rules for consent requirements for ad identifiers with legal (this is out of scope for UX; flagged for the legal research track).

---

## 6. Accessibility, one-handed use, low-end performance and the Indian market

### 6.1 Accessibility
- **Touch targets ≥ 48 × 48 dp**; use Material `IconButton` or padding to reach that size [32].
- **TalkBack:** live-region announcements in AR: "Width forty-five point five centimetres, plus or minus half a centimetre, good accuracy." Use specific click labels ("Add point" rather than "Double tap to activate") and **custom actions** on gallery cards (Place, Share, Delete) [32]. Test with Switch Access [32].
- **Non-visual and low-vision paths:** manual dimension entry for everything; large-text support in AR overlays (scale with the system font up to 200%, and wrap the chip text); a high-contrast reticle option; never rely on colour alone (badges use shapes).
- **Reduce motion:** respect the system animation setting; replace the looping coaching animation with static frames.
- **Sound cues** (Android, because of the ARCore haptics caveat [4]) can be switched off; iOS uses haptics.
- **Colour-vision safety:** the signal colour must pass for protanopia and deuteranopia; don't encode Good/Fair/Rough as green/amber/red alone.

### 6.2 One-handed use
- AR layout (portrait):
  ```
  ┌──────────────────────────────┐
  │ ✕         ± chip    ☀ light  │  ← status only (top, no frequent taps)
  │                              │
  │            ( + )             │  ← crosshair fixed at centre
  │       45.5 cm ± 0.5 ●        │
  │                              │
  │ ↶ undo   [  ⊕ ADD  ]   ❄ freeze│  ← all primary actions in the thumb zone
  │  cm/in        mode ▸         │
  └──────────────────────────────┘
  ```
- Primary action (Add point / Capture / Place) is **bottom centre, 72 dp**; a left-handed mode mirrors undo and freeze.
- **Volume keys as a shutter** for Add point (a familiar camera convention) are an optional setting.
- Freeze frame allows two-handed refinement *after* the phone is lowered.

### 6.3 Low-end and mid-range Android performance
- **Capability tiers** decided at runtime (Depth API support varies by device [27]):
  - Tier A: ARCore + Depth → full features.
  - Tier B: ARCore without depth → plane-based measuring; card calibration strongly suggested.
  - Tier C: no ARCore → photo + reference card measurement (2D) and manual entry; hide AR modes (Apple's "only offer AR features on capable devices" [3]).
- Render at 30 fps when idle, and keep the UI overlay to Compose with minimal recomposition; avoid blur or glass effects over the camera feed (costly and "AI-looking").
- Release the camera immediately when leaving AR; save in the background.
- **App size:** keep the base APK small. Deliver heavy modules (e.g. a future photogrammetry mode) and Indic fonts beyond Devanagari as on-demand modules; the user may also need to install Google Play Services for AR, so show its download size ahead of time.
- **Thermal:** after about 5 minutes of continuous AR, show a gentle "Phone getting warm? Take a break. Your progress is saved."
- **Offline-first:** measuring, saving and the gallery work fully offline; sync and sharing links when online.

### 6.4 India-specific considerations
- **Market:** the mass-budget band (US$100–200) is still about 41% of shipments in 2025 [33]; ₹10k–₹20k is reportedly the largest segment by units [34]. Design for 6 GB RAM / mid-tier SoC phones, roughly 6.5", 1080p or 720p screens, often in harsh daylight or poorly lit interiors.
- **Languages:** 98% of India's 886 M internet users access some content in Indic languages, and 57% of urban users prefer vernacular content [35]. **Launch in English + Hindi**; then Tamil, Telugu, Bengali, Marathi, Kannada and Malayalam. Use Android per-app language settings; allow for text expansion of about 30–40% in Indic scripts; use Noto Sans for each script. Keep numerals as Western digits (0–9) by default, which is standard in Indian retail; units abbreviations stay Latin ("cm", "ft").
- **Data costs:** entry plans of about ₹299 for 1.5 GB/day [36]. Data is cheap, but daily caps and patchy networks are real. Show download sizes, offer a Wi-Fi-only option for catalogue 3D models, compress models (glTF/GLB with mesh and texture compression), and keep shared images under 300 KB (WhatsApp-friendly).
- **Units (context-aware defaults, all switchable):**

| Context | Default | Secondary |
|---|---|---|
| Room dimensions, ceiling height, person height | ft + in (5′ 8″) | m |
| Floor area | sq ft | m² |
| Furniture, objects | cm | in |
| Photo frames, posters | in (8 × 10 in is how frames are sold) | cm |
| TVs and monitors | **Diagonal in inches**, plus W×H in cm (Croma-style "108 cm (43 inch)") | — |
| Small objects | cm with 1 decimal (mm only when ± ≤ 2 mm) | in with 1/8" |

  Always show **primary + secondary** on the dimension card: `61.0 cm (24 in)`.
- **Sharing:** WhatsApp is the default target in the share sheet: an annotated image plus a short text ("Frame: 45.7 × 61.0 cm (18 × 24 in) ± 0.5 cm. Measured with SpaceView"). Also offer a short link to a web 3D viewer.
- **Offline retail moments:** the "seen in a café or shop" use case means scans happen in public places with mixed lighting and little time. Hence the 30-second target, one-handed operation, and no ads during capture.
- **Trust signals** matter in a market wary of fake claims: show the method, the ±, and the retailer verification badge.

---

## 7. Visual direction: "precision instrument", not "AI app"

### 7.1 Principles
- **It reads like a tool, not a toy**: think steel rule, vernier caliper, drafting sheet. Confident, quiet, exact.
- **Content first**: the camera view is the hero; the UI is thin, sharp and minimal (Apple: "Let people use the entire display" [3]).
- **Show the working**: dimension lines, extension lines, tick marks and tolerances are the decoration.

### 7.2 Concrete choices

| Element | Direction | Avoid |
|---|---|---|
| Palette | Graphite (#16181B-ish) / paper white (#F6F5F1-ish), neutrals; **one signal colour**, high-visibility yellow (#FFD400-ish) or safety orange, used only for the reticle, active measurement and the primary CTA. Status colours are desaturated. | Purple-blue gradients, neon glows, glassmorphism, "aurora" backgrounds |
| Typography | A sturdy grotesk for UI (e.g. Inter or IBM Plex Sans) + **tabular / monospaced numerals** for measurements (IBM Plex Mono / JetBrains Mono) so digits don't jitter while updating; Noto Sans Devanagari and other Indic scripts with matched x-height | Rounded "friendly AI" fonts; numbers in proportional figures that shift width |
| Dimension graphics | Engineering-drawing grammar: thin 1.5 dp lines, small arrowheads or ticks at the ends, extension lines, the label centred *on* the line with a solid backing pill for legibility; ± in smaller weight | Big floating 3D text, emojis, sparkle icons |
| Iconography | Outline icons with square terminals, consistent 1.5–2 dp stroke; custom glyphs for modes (line, height, box, area) drawn like drafting symbols | Sparkle ✨ and "magic wand" icons, robot mascots |
| Motion | Short (150–200 ms), mechanical easing; snapping feels like a detent (a tiny overshoot), counters roll digit by digit | Bouncy, playful, or long lottie animations |
| Surfaces | Flat cards, hairline borders (1 dp), 8–12 dp corner radius max, solid backgrounds behind text over camera (a 70–85% opaque graphite pill) | Frosted blur over camera (also expensive on low-end GPUs) |
| Copy tone | Factual, short, second person: "Fair accuracy. Move closer." | "AI-powered!", "Magic measure", exclamation marks, over-promising |
| Item cards | Photo + a crisp spec line in mono numerals + provenance badge, like a product spec sheet | Generic stock-style illustration cards |
| Brand moments | Ruler tick-mark pattern used sparingly (splash, empty states); the app icon could be a crosshair in a corner bracket | Gradient blob logos |

### 7.3 Light and dark
- AR screens are always "dark chrome" (graphite pills over the camera) for legibility.
- Non-AR screens follow the system theme: paper-white light theme (feels like a spec sheet) and a graphite dark theme. Keep contrast at WCAG AA or better for all text, including over the camera feed (ensured by solid pills).

---

## 8. Open questions for other research tracks
1. **Achievable accuracy by device tier**: we need a bench test (10 target phones × 5 object types × 3 distances) to set the Good/Fair/Rough thresholds and the honest marketing copy.
2. **Card calibration feasibility** on Tier B devices (ARCore without depth): expected improvement.
3. **3D representation for partner catalogues**: what format do retailers have (glTF, USDZ, only photos plus dimensions)? This decides whether "View in your room" shows a real model or a dimensioned card.
4. **Legal**: DPDP consent for camera data, location tagging and ad IDs; partner co-branding rules; whether showing third-party ads next to retailer content is contractually allowed.
5. **Pricing for Remove ads** and partner commercial models (CPA on "Buy at partner" taps, listing fees).

---

## Sources

1. 9to5Google, "Google pulls the plug on ARCore-powered 'Measure' app" (June 2021). https://9to5google.com/2021/06/08/google-measure-ar-app-sunset/ ; also MobileSyrup https://mobilesyrup.com/2021/06/08/google-ar-measure-app-support-ended/
2. Google ARCore design, "Content placement". https://developers.google.com/ar/design/content/content-placement
3. Apple Human Interface Guidelines, "Augmented reality". https://developer.apple.com/design/human-interface-guidelines/augmented-reality
4. Google ARCore, "Performance considerations / runtime" (slow movement, avoid haptics, UX feedback). https://developers.google.com/ar/develop/runtime
5. AdMob Help, "Disallowed interstitial implementations". https://support.google.com/admob/answer/6201362
6. SlashGear, "How accurate is the iPhone's measuring app?" https://www.slashgear.com/1884872/how-accurate-iphone-measuring-app/ ; Kirkville, "Apple's Measure app and accuracy" https://kirkville.com/apples-measure-app-and-accuracy/
7. Apple Support, "Measure dimensions with iPhone". https://support.apple.com/en-mz/guide/iphone/iphd8ac2cfea/ios ; "Use the Measure app" https://support.apple.com/en-asia/102468
8. Moasure Docs, "Understand accuracy". https://docs.moasure.com/en/moasure/understand-accuracy
9. Moasure Docs, "Moasure accuracy" (1 & 2 series). https://docs.moasure.com/en/moasure-1-and-2-series/your-moasure-device/moasure-accuracy
10. LiDAR News, "Polycam Vision 25" (Android photogrammetry only). https://lidarnews.com/polycam-vision-25-3d-reality-capture/
11. Google AdMob developer docs, "Interstitial ads" (natural transition points, pre-loading, pausing). https://developers.google.com/admob/android/interstitial ; AdMob Help, interstitial best practices https://support.google.com/admob/answer/6066980
12. CamToPlan App Store listing https://apps.apple.com/us/app/camtoplan-ar-tape-measure/id1292176208 ; JustUseApp reviews of AR Ruler apps https://justuseapp.com/en/app/1288961677/ar-ruler-smart-tape-measure/reviews and https://justuseapp.com/en/app/848139616/cam-ruler-ar-tape-measure/reviews
13. Magicplan Help, "How accurate is magicplan?" https://help.magicplan.app/how-accurate-is-magicplan
14. Apple guidance on approximate results, as reported in [6].
15. AdMob Help, "Banner ad implementation guidance" / accidental clicks. https://support.google.com/admob/answer/6275345 ; AdMob blog https://admob.googleblog.com/2016/02/how-to-implement-banner-ads-right-way.html
16. TechCrunch / Mixed News coverage of IKEA Kreativ. https://mixed-news.com/en/ikea-app-now-deletes-your-furniture-and-replaces-it-with-your-own/ ; https://www.techradar.com/news/ikeas-new-iphone-app-can-delete-and-replace-your-old-furniture
17. Android Police hands-on, IKEA Place. https://www.androidpolice.com/2018/03/19/hands-ikea-place-uses-ar-see-ektorp-goes-lovbacken/ ; Daily Dot https://dailydot.com/debug/ikea-place-app/
18. Pratt IxD, "Design critique: IKEA Kreativ (iOS app)" (2024). https://ixd.prattsi.org/2024/02/design-critique-ikea-kreativ-ios-app/
19. Engadget, Amazon AR Room Decorator. https://www.engadget.com/amazon-ar-shopping-furniture-home-decor-171005257.html ; Daily Dot, Amazon AR View https://dailydot.com/debug/amazon-ar-view-app
20. Magicplan reviews: JustUseApp https://justuseapp.com/en/app/427424432/magicplan/reviews ; Capterra https://www.capterra.com/p/210335/magicplan/reviews/ ; Illustrarch review https://illustrarch.com/articles/architectural-technology/73956-magicplan-review.html
21. SkyeBrowse, "Polycam review" (2026). https://www.skyebrowse.com/news/posts/polycam-review
22. 3DWithUs, Polycam LiDAR vs photo mode https://3dwithus.com/polycam-3d-scanning-app-review-lidar-vs-photo-mode ; Engineering.com https://www.engineering.com/?p=46266
23. Canvas App Store listing https://apps.apple.com/us/app/canvas-lidar-3d-measurements/id1169235377 ; canvas.io https://canvas.io/sketchup ; Geo Week News https://www.geoweeknews.com/articles/app-offers-scan-cad-350-3d-sensor/
24. Flipkart Stories, "Flipkart Camera" https://stories.flipkart.com/vision-for-india-flipkart-camera-immersive-shopping/ ; IndiaAI on Lenskart https://indiaai.gov.in/article/lenskart-reimaged-the-way-we-look-at-eyeware-thanks-to-its-ai-chops ; Entrepreneur India on Pepperfry AR https://india.entrepreneur.com/?p=79914
25. NewScrewdriver, "Window shopping ARCore design guidelines" (summary of Google's AR design guidelines, including "don't make users walk backwards"). https://newscrewdriver.com/2020/07/19/window-shopping-arcore-design-guidelines/
26. Correll & Gleicher, "Error Bars Considered Harmful". https://vis.mit.edu/classes/6.859/readings/pdfs/Corell-ErrorBarsConsideredHarmful.pdf
27. Google ARCore, Depth / Geospatial Depth (device support checked at runtime; typical range 20–30 m). https://developers.google.com/ar/develop/java/depth/geospatial-depth
28. Play Console Help, "Unexpected/disruptive interstitial" policy guidance. https://support.google.com/googleplay/android-developer/answer/12271244
29. Play Console Help, "Better Ads Experiences" policy (15-second rule). https://support.google.com/googleplay/android-developer/answer/9857753
30. Google Publisher Policies, "Google-served ads on screens without publisher-content". https://support.google.com/publisherpolicies/answer/11112688
31. AdMob Help, "Frequency capping". https://support.google.com/admob/answer/6244508 ; Firebase tutorial https://firebase.google.com/docs/tutorials/optimize-ad-frequency/step-1
32. Android Developers codelab, "Accessibility in Jetpack Compose". https://developer.android.com/codelabs/jetpack-compose-accessibility
33. IDC, India smartphone market 2025 (Feb 2026). https://www.idc.com/resource-center/press-releases/india-smartphone-market-2025-2026/
34. TelecomLead, H1 2025 IDC data (₹10k–20k largest segment). https://telecomlead.com/smart-phone/india-smartphone-market-grows-2-in-h1-2025-as-premium-segment-surges-idc-122676
35. IAMAI-Kantar "Internet in India 2024" via The Week. https://www.theweek.in/wire-updates/business/2025/01/16/dcm51-biz-iamai-internet-usage-report.html ; Storyboard18 https://www.storyboard18.com/digital/surge-in-indic-language-adoption-leads-to-55-surge-in-rural-internet-users-53632.htm
36. The Ken, "Jio, Airtel hike entry-level pricing" (Aug 2025). https://the-ken.com/long_and_short/jio-airtel-hike-entry-level-pricing-but-lose-cheapest-data-tag-in-emerging-markets/

*Caveats:* the competitor complaint data is mostly from individual reviews and older coverage, not systematic app-store analysis; the ID-1 card size is the ISO/IEC 7810 standard, and PVC Aadhaar being ID-1 should be verified; the Good/Fair/Rough thresholds and frequency caps are proposals to validate with testing and A/B experiments.
