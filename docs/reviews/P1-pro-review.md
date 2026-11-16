# P1 Pro foundation: review (security + correctness)

- **Reviewer:** FRIDAY, §4.B review with §4.D (MASVS), §4.K (CI) and §7.
- **Scope:** Builder B's uncommitted diff against `4f8ec1f`.
  - `app/pro/*`, `app/billing/*`, `ads/AdsSdk.kt`, `ads/AdsManager.kt`.
  - `AppContainer.kt`, `MainActivity.kt`, `settings/SettingsScreen.kt`.
  - The manifest and both backup XMLs, `app/` and `data/build.gradle.kts`, `libs.versions.toml`, `ci/*`, `android.yml`.
  - `strings_pro.xml` (EN and HI) and the tests under `app/src/test/.../{pro,billing,ads}`.
  - Builder A's Place, M4 and recon code is out of scope.
- **Risk class:** HIGH (payments, §4.D).
- **Method:** I read the tests first, then the diff, then every call site.
  - I did not run Gradle (orchestrator's instruction).
  - Build and test evidence is the orchestrator's green run (app 217 tests, data 63) plus the result XMLs already on disk. Paths are cited below.

## Summary

| Severity | Count |
|---|---|
| BLOCKER | 0 |
| MAJOR | 3 |
| MINOR | 7 |

The verification core is sound:

- The RSA check is correct and fails closed.
- Pending purchases are never granted or acknowledged.
- Refunds, revocations and account hold take Pro away at the next query.
- Release builds cannot reach the fake gateway.
- The cache is excluded from backup and transfer.
- CI pins resolve to the right tags.

The three MAJORs are all on the edges of the state machine and the product surface:

- **Cold start:** a common return path still initialises AdMob for a Pro user.
- **Clock rollback:** winding the clock back inside the grace window extends the grace without limit.
- **Paywall honesty:** the paywall sells a gallery limit that isn't enforced.

**Top security risk:** offline-grace abuse through the wall clock (MAJOR 2). It needs no tools and no APK patching, so it sits below the ADR-004 threat bar ("patched APK") that the design accepts. It is also a stated invariant (§4.4) that the code and its test claim to satisfy.

---

## Verified correct (evidence)

### Purchase verification

**Signature check (`PurchaseVerifier.kt:22-64`):**
- Uses `KeyFactory("RSA")` with `X509EncodedKeySpec`, then `Signature("SHA1withRSA")`.
- Runs over the UTF-8 bytes of `Purchase.originalJson` (`PlayBillingGateway.kt:157-158`).
- This is Play's scheme. Only `java.security` is used; there is no custom crypto.
- A blank, garbage or non-base64 key or signature makes `publicKey` return null and `verify` return false.
- Covered by `PurchaseVerifierTest` (14 tests: flipped byte, tampered data, wrong key, empty, non-base64, blank and garbage key).

**Release fails closed:**
- `PLAY_LICENSE_KEY` is required when `REQUIRE_RELEASE_CONFIG=true` (`app/build.gradle.kts:37-38`).
- When the key is missing or unparsable, `purchasesEnabled=false` (`AppContainer.kt:106`). The paywall then shows NOT_ON_SALE, `purchase()` returns `NotConfigured` without launching, and the verifier rejects everything. Nothing is granted and nothing is sold.
- Debug builds take the fake gateway's own key (`AppContainer.kt:92-93`).

**Can release use `FakeBillingGateway`?** No.
- Release hard-codes `FAKE_BILLING = "false"` (`app/build.gradle.kts`, release block). Only `debug` and `release` build types exist; there is no `initWith`.
- The R8 output from the 2026-11-06 17:01 release build confirms it:
  - `FakeBillingGateway`, `FakeBillingControlsKt`, `$NextOutcome` and `$Companion` appear in `app/build/outputs/mapping/release/usage.txt` (removed).
  - They have no class mapping in `mapping.txt`. The only hits are `$$compose` source-info lines, which are mapping-only.
- The Settings debug panel sits behind the same constant (`SettingsScreen.kt` `debugPanel`), and `billing` is always the Play gateway in release.

**Pending is never granted.**
- `fromPurchases` grants only `state == PURCHASED && !suspended` (`Entitlement.kt:137`).
- PENDING maps to `Entitlement.Pending`. It holds no cache and is never acknowledged.
- `UNSPECIFIED` is ignored.
- Tests: `pending purchase gives Pending…`, `pending payment then bank approval unlocks Pro`, and `forged pending purchase does not show Pending`.

**Acknowledgement:**
- Only verified, PURCHASED, unacknowledged purchases are acknowledged (`Entitlement.kt:153`).
- A failure is retried on every refresh (start, foreground every 10 minutes at most, paywall open, Restore).
- The cache records success (`EntitlementRepository.kt:259-271`).
- Tests: `acknowledgement failure is retried after a process restart` and `forged purchase … never acknowledged`.

**Play-reported fields can't be forged on their own.**
- `OwnedPurchase.state`, `productIds` and `suspended` come from the `Purchase` object, which the library parses out of the signed `originalJson`.
- So a fake billing service can't change them without breaking the signature. Only a patched APK can, and ADR-004 accepts that.

**Products:**
- Grants are limited to `ProCatalog.proProductIds = {"pro"}`.
- `queryActive` asks for SUBS only, and the prepaid `pass30` is a SUBS base plan.
- Suspended (held or paused) subscriptions are excluded by default and also filtered.
- `enablePendingPurchases(...enablePrepaidPlans())` is set (`PlayBillingGateway.kt:58`).

**`obfuscatedAccountId`:** not used. That is correct for an account-less app: a per-install id would break Restore on reinstall. See MINOR 4 for the consequence.

### Entitlement state machine

- **Refund, revoke or expiry:** `QueryResult.Ok` is authoritative. A missing purchase means Free and the cache is cleared (`Entitlement.kt:155-159`). Test: `refund or revocation turns Pro into Free…`.
- **Grace boundary:** at 7 d it is still Pro; at 7 d + 1 ms it is Free (`Entitlement.kt:122`). Tests cover 6 d 23 h 59 m, exactly 7 d, and 7 d + 1 ms, so an off-by-one would be caught.
- **Prepaid pass expiry:** Play stops returning the purchase, so the next Ok query means Free. While offline, the pass can run up to 7 days past its expiry (accepted grace semantics).
- **Purchase updates never take Pro away**, and a forged update never grants (`Entitlement.kt:92-106`, two tests).
- **Concurrency:** one `Mutex` covers refresh, update and acknowledge. Grants are idempotent by token hash. Test: `refresh racing a purchase grants once and acknowledges once`.
- **Backup and transfer:** `datastore/spaceview_entitlement.preferences_pb` is excluded in `backup_rules.xml` and in both the `<cloud-backup>` and `<device-transfer>` sections of `data_extraction_rules.xml`.
  - The path matches `ENTITLEMENT_DATASTORE_NAME` and DataStore's `files/datastore/<name>.preferences_pb` layout.
  - It is enforced by `ProPersistenceTest.entitlementFileIsExcludedFromBackupAndTransfer`.
  - Nit: the XML comments call this `BackupRulesTest`.
- **Tamper evidence:** the cached record is plain DataStore holding a SHA-256 of the token, with no MAC. Editing it requires root, which is inside the accepted ADR-004 threat model, and it can grant at most 7 days. This is acceptable as recorded (`Entitlement.kt:46-47`, `shortcut:`).
- **Unknown at cold start:**
  - `AdsManager.adsAllowed` starts false, and `canShowAds` needs `adsAllowed`, so there is no ad while Unknown.
  - A cached Pro inside the grace window is published as soon as the DataStore read finishes (`EntitlementRepository.kt:126-128`), so in the common case there is no Pro flash and no ad flash.
  - The exception is MAJOR 1.

### Gating and ads

- **`FeatureRules.evaluate` (`FeatureGate.kt:48-59`):** only `Pro` gets the Pro allowance, so Unknown, Free and Pending gate as Free. The count limits are 20, 3 and 2 (`ProCatalog.kt:88-90`). `check()` waits at most 1 s, then falls back to Free.
- **Lapse never hides data:** gates only answer questions about *new* actions. No read, view, share or delete path consults them. FeatureGateTest has a 40-item case.
- **Ads for Pro:** `onEntitlement(Pro)` drops the interstitial and the pending chance and sets `canShowAds=false`. `BannerAd` returns early, which disposes the `AdView` (`BannerAd.kt:58-59`), so banners disappear after a purchase.
  - `startSdkIfAllowed` and `gatherConsent` short-circuit on `!adsAllowed` before any UMP call.
  - The Settings privacy row is hidden for Pro.
- **MeasureVisitTracker (contract K2):** `MeasureVisitTracker.kt` has no diff. The Free path is unchanged, and the 18-test suite gained cases (`MeasureVisitTrackerTest`, +44 lines).

### Paywall (Play policy)

- **Close button:** a 48 dp `IconButton` with a content description, first in focus order. System Back also closes.
- **Prices:** every price comes from `ProductDetails`, and the strings contain none.
- **Yearly price:** shown as "₹799 / year". It is not presented as a monthly cost.
- **Terms:** the trial text states its length, what it converts to, the price and how to cancel. The prepaid text says it doesn't renew.
- **Links and Restore:** the Manage deep link (`…/subscriptions?sku=pro&package=…`) and Restore appear on both the paywall and Settings.
- **Buy is disabled while a payment is Pending.**
- **No AR, no hostage:** the only `openPaywall` caller today is Settings, and Place passes the paywall callback but never calls it. Closing the paywall pops back to where the user was.
- **Hindi:** 74/74 keys, and the placeholders are reordered correctly (for example `pro_terms_trial` uses `%3$s %2$s`).
- **TalkBack:**
  - The plans form a `selectableGroup` of rows with `Role.RadioButton`.
  - The title is a heading.
  - The message uses a polite live region.

### CI and supply chain

**Action pins:** every pin matches its tag. I checked with `git ls-remote --tags` today:

| Action | Pinned SHA | Tag |
|---|---|---|
| `actions/checkout` | `3d3c42e5…` | v7 |
| `actions/setup-java` | `b843ebf9…` | v6 |
| `gradle/actions` | `8da46b53…` | `v6^{}` (the peeled commit, which is correct) |
| `actions/upload-artifact` | `cf430e03…` | v7 |

**Secrets:**
- `PLAY_LICENSE_KEY` is only tested with `[ -z ]` and never echoed.
- Fork PRs get no secrets on `pull_request`. Their release build then has a blank key, which fails closed, with a warning.
- The workflow keeps `permissions: contents: read`.
- The key is a public key and ships in the APK by design.

**Dependencies:** there are 12 new transitive release dependencies; `ci/release-dependencies.txt` is updated. Billing 9.1.0's POM pulls in `transport-*` and `play-services-location:19.0.0`. I unzipped the AARs from the Gradle cache:

| AAR | What it declares |
|---|---|
| `play-services-location-19.0.0` | No permissions |
| `places-placereport-17.0.0` | No permissions |
| `transport-backend-cct` / `transport-runtime` | `INTERNET` and `ACCESS_NETWORK_STATE` (already allowed); services and receiver `exported="false"` |
| `billing-9.1.0` | `com.android.vending.BILLING`, `<queries>`, two `ProxyBillingActivity` entries with `exported="false"` |

The merged release manifest (`app/build/intermediates/merged_manifest/release/.../AndroidManifest.xml`, 16:53) has **no location permission**, and its permission set equals `ci/allowed-permissions.txt` (8 entries).

**androidTest compile:** `:data:compileDebugAndroidTestKotlin` was added to the unit and lint step.

### Robolectric migration test

**Scope of the Windows exclusion:**
- The filter is inside `:data`'s `tasks.withType<Test>()` and keys on the Gradle JVM's `os.name`.
- It excludes only `com.spaceview.data.SpaceViewMigrationTest`, so Linux CI runs it.
- `isFailOnNoMatchingTests=false` only matters for `--tests` runs on Windows.

**Does it exercise both migrations?** Yes.
- It calls `runMigrationsAndValidate(DB, 2, true, MIGRATION_1_2)` and `(DB, 3, true, MIGRATION_2_3)`, plus a full chain from v1 to `LATEST_VERSION`.
- Each call validates against the exported `data/schemas/.../{1,2,3}.json`, added to test assets in `data/build.gradle.kts`.
- It then opens real Room and reads the rows back.
- `robolectric.properties` pins `sdk=35` (below compileSdk 37). This is reasonable.

---

## Findings

### BLOCKER

None.

### MAJOR

**1. A Pro user whose cache is older than 7 days gets UMP and `MobileAds.initialize` at cold start: `EntitlementRepository.kt:118-128`, `AdsManager.kt:147-149`**

- **Problem:**
  - `start()` publishes the cache only when it is Pro inside the grace window.
  - Otherwise the entitlement stays `Unknown` until Play answers.
  - Two independent 1 s timeouts both resolve a slow answer to Free:
    - the repository's `compareAndSet(Unknown, effective(cached))`, which gives Free for a stale cache;
    - `AdsManager`'s `withTimeoutOrNull(1000) ?: Free`.
  - The Free path calls `gatherConsent` (UMP `requestConsentInfoUpdate`, and the consent form where required), then `MobileAds.initialize` once `canRequestAds()` is true. The SDK can't be de-initialised.
- **Scenario:**
  - A yearly subscriber who measures a few times a month opens the app 8 days after the last open.
  - The cache is 8 days old, so `effective` gives Free.
  - The first Play connection plus `queryPurchasesAsync` takes over 1 s, which is routine on a cold low-end phone or a slow network (connect timeout 10 s).
  - At 1 s the user becomes Free, so UMP runs (in the EEA/UK a consent form can appear to a paying user) and `MobileAds.initialize` runs on the cold-start path.
  - Play answers Pro at about 1.5–3 s. If the Home banner loaded in between, it flashes.
  - The same happens on every reinstall or new phone, where there is no cache at all.
  - The approved decision is "AdMob never initialised for Pro". `AdsManagerProTest` ``Unknown - no ad for 1 s, then treated as Free`` codifies the current behaviour.
- **Fix (small):** let ads wait for a *settled* entitlement instead of 1 s.
  - Add `EntitlementRepository.settled: StateFlow<Boolean>`, set true after the first APP_START refresh returns Ok or Failed.
  - `AdsManager.start` waits for `settled` with a generous cap (for example 12 s, above `CONNECT_TIMEOUT_MS`), then follows the entitlement.
  - Ads have no cold-start urgency: Free users see their first banner a few hundred ms later.
  - Gates keep their 1 s wait, since Unknown already gates as Free.
  - Replace the codifying test with "Unknown for 5 s, then Pro: zero consent requests and zero inits", plus a stale-cache case.
- **If skipped:** the headline Pro promise ("no ads, AdMob never initialised") breaks for the most common return pattern of an episodic utility app. That is also a privacy surface (consent form, ad-SDK network traffic) for paying users.

**2. Rolling the clock back inside the grace window extends offline Pro without limit: `Entitlement.kt:118-127`**

- **Problem:**
  - `effective()` rejects a clock that is more than 5 minutes *before* `confirmedAt`.
  - It trusts any clock between `confirmedAt` and `confirmedAt + 7 d`.
  - Nothing records the latest time the app has seen, so moving back to a point inside the window isn't detected.
  - Both the KDoc ("winding the clock back can never extend the grace") and ARCHITECTURE §4.4 ("a rollback never *extends* grace") claim otherwise.
  - The test ``clock moved back past the confirmation never extends grace`` checks only rollbacks to before `confirmedAt`.
- **Scenario:**
  1. Buy `pass30` once (₹149) and let the app confirm it on day 0.
  2. Disable the Play Store app (Settings → Apps → Disable, allowed on most phones). `startConnection` then returns `BILLING_UNAVAILABLE`, which becomes `QueryResult.Failed`, which falls back to the cache.
  3. Turn off automatic time and set the date to day 1.
  4. Whenever the clock nears day 7, set it back to day 1.

  Pro (no ads, unlimited gallery) then lasts indefinitely, with no root, no patched APK and no tools. That is below the ADR-004 bar ("a patched APK skips the check").
- **Fix:** persist a high-water mark and reject time travel below it.
  - Add `lastSeenMillis` to `CachedEntitlement`. On every evaluation (start, foreground, timeout path), write `max(lastSeen, now)`.
  - `effective()` returns Free when `now < lastSeen − CLOCK_SKEW_TOLERANCE_MS`, and measures age as `max(now, lastSeen) − confirmedAt`.
  - The grace is then bounded by 7 days of *app-observed* time.
  - Optionally also add elapsed realtime within one boot.
  - Add reducer tests: "clock set back to inside the window after `lastSeen` → Free" and "small NTP correction under `lastSeen` → still Pro".
  - Correct the KDoc and the §4.4 row.
- **If skipped:** the 7-day grace becomes an unlimited free Pro for anyone willing to disable the Play Store. The revenue at risk is small (ADR-004), but the code ships a false security claim in the HIGH-risk path.

**3. The paywall sells "Unlimited gallery saves", but the 20-item Free cap is enforced nowhere: `ProCatalog.kt:106`, with no call site for `featureGate`**

- **Problem:**
  - `GALLERY_UNLIMITED` is `shipped = true`, so `ProCatalog.advertised` lists it as a Pro benefit (`pro_benefit_gallery`).
  - `pro_reason_gallery` tells users "The free gallery holds 20 items."
  - But `AppContainer.featureGate` has no consumer: grep finds no `featureGate` or `check(GALLERY_UNLIMITED` outside `pro/`.
  - The project's own rule (`ProCatalog.kt:51-53`, PLAN §9 "Pro honesty") is to flip `shipped` in the same change that ships the gate.
  - This is the brief's known gap. Its impact is the false advertising, not lost data.
- **Scenario:** a Free user saves 35 items unhindered. The paywall (from Settings → Free plan) tells them Free stops at 20 and that Pro adds "Unlimited gallery saves", which they already have. A buyer who paid partly for that benefit got nothing extra. That is a misrepresentation risk under Play's subscription and deceptive-behaviour policies, and a support-ticket and refund risk.
- **Fix:** either set `shipped = false` for `GALLERY_UNLIMITED` until the gate lands (the paywall then advertises only No ads), or enforce the gate in these three places:
  1. **Scan entry, before any AR session (ADR-005 rule 1):** in `AppNavHost.kt:107` (Home `onScan`) and `AppNavHost.kt:202` (Gallery `onScan`), before `navigate(Routes.SCAN)`:
     - `when (featureGate.check(GALLERY_UNLIMITED, galleryCount)) { NeedsPro -> openPaywall(GALLERY_UNLIMITED); else -> navigate(SCAN) }`
     - This needs a cheap `@Query("SELECT COUNT(*) FROM gallery_items") suspend fun count()` and an `observeCount()` for the "18 / 20" counter. No count query exists today, and `observeAll().size` would load every row.
  2. **Save backstop:** in `ScanViewModel.save` (`ScanViewModel.kt:234-243`), before `store.save(draft)`, on the non-AR Review step. This covers a lapse or a second device between entry and Save.
     - On NeedsPro, **keep the draft** in the ViewModel and show "Gallery full" with *Upgrade* (paywall; on return with Pro, save), *Share* and *Discard*.
     - Never drop the measured result (§5.1 rule 2: never hold a result hostage).
     - The cleanest single point is a gating `ScanStore` decorator in `:app` (`:data` must not depend on `:app/pro`).
  3. **Every other gallery insert:** the same check goes before `GalleryRepository.saveTemplate` and `saveMesh` (K10, Builder A). `saveMesh` also checks `DEVICE_SCAN` against a mesh-item count (`FREE_DEVICE_SCANS = 3`) when that feature ships.
  - Existing items above 20 stay viewable, placeable, shareable and deletable. Only the next save is gated, as FeatureGateTest already specifies.
- **If skipped:** the first paid release advertises a limit and a benefit that don't exist.

### MINOR

**4. A genuine signed purchase can be replayed indefinitely; prepaid expiry and `packageName` are not checked: `Entitlement.kt:135-139`**

- **Problem:**
  - The verifier proves only that Google signed the JSON. A genuine `originalJson` and signature (for example one ₹149 `pass30`, extracted on a rooted phone and shared) can be served forever by a fake billing service on any device, because nothing binds it to an account or a time.
  - ADR-004's table only discusses *forged* JSON.
- **Scenario:** a leaked expired pass grants Pro on every device that runs a billing emulator.
- **Fix:**
  - For non-auto-renewing purchases, parse `purchaseTime` from the *signed* JSON and refuse it when `purchaseTime + 31 d < now`. The same value also caps the offline grace at the pass's real end.
  - Optionally check `packageName == BuildConfig.APPLICATION_ID` (cheap belt-and-braces).
  - Record "replay of a genuine purchase" as an accepted residual risk for auto-renewing plans in ADR-004.
- **If skipped:** this accepted risk class stays slightly wider than documented.

**5. `PLAY_LICENSE_KEY` isn't validated at build time; a wrong but parsable key means users are charged, never granted, then refunded: `app/build.gradle.kts:37-38`, `AppContainer.kt:106`**

- **Scenario:** the CI secret holds another app's licence key, or a stale one. `purchasesEnabled` is true, every real purchase fails the signature check, the paywall says "The purchase didn't complete", Play refunds after 3 days, and 1-star reviews follow.
- **Fix:**
  - When `REQUIRE_RELEASE_CONFIG=true`, parse the key in Gradle (base64 → `X509EncodedKeySpec` → RSA) and fail the build if it is invalid.
  - Add a release-gate checklist line: one licence-tester purchase on the internal track turns Pro on before promotion (already in §4.10 device tests; make it gating).

**6. Cold start does RSA key parsing twice on the main thread in `onCreate`: `MainActivity.kt:32`, `AppContainer.kt:97-106`**

- **Problem:**
  - `container.ads.startAfterFirstFrame(this)` touches `ads`, which builds `entitlements` eagerly in `onCreate`, before the first frame.
  - That calls `PurchaseVerifier.forKey(key)` and `publicKey(key)`: two `KeyFactory("RSA")` parses, and the first JCA provider load.
  - In debug it also generates a 1024-bit RSA key pair and reads SharedPreferences on main.
  - The KDoc and MainActivity both say no billing work happens before the first frame.
- **Scenario:** a release build with a real key adds an estimated 5–30 ms (unmeasured) to the ≤ 800 ms cold-start budget.
- **Fix:**
  - Parse the key once (`val key = PurchaseVerifier.publicKey(...)`, and derive `purchasesEnabled` from it).
  - Make the verifier lazy, or move `container.ads.startAfterFirstFrame` into the posted first-frame callback.
  - Verify with `am start -W` ×3, as §4.9 requires.

**7. The paywall Close button scrolls off screen: `PaywallScreen.kt:106-113`**

- **Problem:** `CloseBar` is the first child of the `verticalScroll` column.
- **Scenario:** on a small phone, or at 200% font size, the user scrolls to the plans and the terms, and the only visible dismiss is gone. Play wants a clearly visible dismiss. System Back still works, but it isn't visible.
- **Fix:** move `CloseBar` above the scrolling column, in the same `Surface`.

**8. `purchase()` takes the first outcome on the shared flow, not the one for its own flow: `EntitlementRepository.kt:173`, `:208`**

- **Scenario:** a PENDING purchase from an earlier session is approved and arrives on `updates` while the user is in a new flow. (This is narrow, because Buy is disabled only while the *current* entitlement is Pending.) The new flow then reports the old outcome, and its own outcome is dropped, because there is no subscriber and no replay.
- **Fix:** match on the offer's product and base plan or on a token not seen before, or document it as accepted. The entitlement itself stays correct either way.

**9. Test gaps against the brief's failure modes: `EntitlementReducerTest`, `AdsManagerProTest`**

- **Covered:**
  - A forged signature: the real RSA path runs through `EntitlementRepositoryTest` (``forged purchase from a fake billing service never grants``).
  - Pending to granted.
  - The grace off-by-one.
- **Missing:**
  - A rollback *inside* the grace window (MAJOR 2).
  - `PurchaseState.UNSPECIFIED` never grants.
  - Cold start with a stale cache and slow Play gives zero UMP and zero init calls (MAJOR 1). Today's test asserts the opposite.
  - An `onPurchaseUpdate` that holds a valid purchase for a non-Pro product.
- **Fix:** add those four cases.

**10. Billing 9.1 brings Firelog/datatransport, which sends library diagnostics to Google: `ci/release-dependencies.txt` (+`transport-backend-cct`)**

- **Scenario:** the Data safety form says no diagnostics are collected by SDKs, but the billing library transmits them.
- **Fix:** review the Play Billing SDK's data disclosure at submission (already flagged in §4.8 PRIVACY) and record the result in `docs/`.

---

## Nits (not findings)

- Both backup XMLs refer to "BackupRulesTest". The test is `ProPersistenceTest.entitlementFileIsExcludedFromBackupAndTransfer`.
- `pro_cta_trial` renders "Start 7 days free trial". It should read "Start 7-day free trial", which needs a separate string or a plural-free phrasing. The Hindi is fine.
- `PaywallViewModel.buy` doesn't guard Pending itself; the guard is in the UI only. Mirror it in the ViewModel.
- `FakeBillingGateway` signs `purchaseState` as an enum name, while Play uses ints. That's harmless today because nothing parses the JSON, but MINOR 4's fix will parse it, so align the fake then.

## Pre-existing (not regressions)

- None found in the scoped files.

## Verdict

**Approve the foundation for merge, but not for release.**
- The verification, pending, acknowledgement, refund and backup paths are correct and well tested.
- Fix MAJOR 1–3 before any build reaches the Play internal track with real products. MAJOR 3 can be closed in one line by setting `shipped = false` until the gate lands.
- MINOR 5 should become part of the release gate.

## Not checked

- **Builds and tests:** I did not run Gradle. Pass and fail evidence is the orchestrator's green run plus the result XMLs dated 2026-11-06 in `app/build/test-results/testDebugUnitTest/`. I did not re-run them against the final tree.
- **The Robolectric migration test has never run:** it is excluded on this Windows host, and CI hasn't run this diff. "Runs on Linux CI" is unverified until the first CI run shows `TEST-com.spaceview.data.SpaceViewMigrationTest.xml`.
- **R8 removal of the fake gateway:** taken from the 17:01 release build's `usage.txt` and `mapping.txt`. I did not inspect the dex, and I didn't re-check after any later edit.
- **Device behaviour:** real Play Billing (licence testers, slow or declined test cards, pending to approved, account hold, a second Google account), UMP in the EEA, the TalkBack pass, the Hindi rendering, and the cold-start time with and without the key.
- **ProGuard/R8 keep rules** for billing reflection (the library ships consumer rules), and the size delta (+184 KiB was reported by Builder B, not measured by me).
- **Builder A's Place, M4 and recon code**, and the `SpaceView.html` and `ARCHITECTURE-v2.md` edits, beyond the sections I cited.
- **CVE or OSV status** of the 12 new transitive dependencies.
