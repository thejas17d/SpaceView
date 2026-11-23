# SpaceView: Pro Tier, Pricing and Profitability

*Research date: 5 November 2026. M3 is done; M4 (place in room) is next. This document builds on `business.md` (2 Nov 2026) and `ui-ux.md` §5 and supersedes their Pro pricing where they differ. Prepared for a solo founder publishing from a personal Play developer account in India.*

> **How to read this.** Figures marked **[est.]** are my working assumptions, not sourced data. USD to INR uses about ₹88/$. "Net" means what reaches the bank after Google Play's service fee **and** GST (18%, included in the Play price), unless it says "pre-GST". `docs/research/3d-capture.md` (the cloud-scan cost study) had not landed at the time of writing, so cloud-scan costs are shown at **₹5, ₹15 (base) and ₹40 per scan**. Section 3.4 gives the rule that converts the real cost into allowances once that report lands.

---

## 0. Executive summary

1. **Never paywall accuracy.** Measure, ±, the quality badge, calibration, history, object scan, place-in-room and the Fits / Tight / Too big verdict all stay **free and unlimited**. They earn the reviews and feed the B2B story. Pro sells **output and convenience**: no ads, unlimited gallery, PDF / hi-res / 3D exports, the full furniture template library, compare, unlimited on-device 3D, cloud backup, and cloud-scan credits.
2. **Price slightly above the old plan, because the product now does more and GST plus Play take about 30%:**
   - **Pro Monthly ₹129**
   - **Pro Yearly ₹799** (≈ ₹67/mo, 7-day free trial)
   - **Pro 30-day Pass ₹149**: prepaid, does not auto-renew. This fits India's "I'm moving / buying a sofa this month" job.
   - **Lifetime ₹1,999** later (v1.x). It never includes cloud credits.
   - These prices are still below Spotify Premium India (₹139/mo) and far below the IN/SEA subscription-app medians ($3.75/mo, $18.32/yr). `business.md`'s ₹99 / ₹599 becomes the low arm of a price test.
3. **Cloud 3D scans must be metered with credits, never "unlimited".** At ₹40 per scan, a heavy Yearly user who uses three scans a month loses money. Included credits are therefore granted **monthly** and capped, with a capped rollover. Top-up packs are priced at **≥ 2× the per-scan cost**. Base case (₹15/scan):
   - Monthly plan: 3 credits/month. Yearly plan: 2 credits/month. Pass: 3 credits.
   - Packs: **3 for ₹99** and **10 for ₹299**.
   - Free users get no free cloud scans; they can buy packs.
4. **Unit economics (base, ₹15/scan, with GST):**
   - Net revenue per payer per month: Monthly ₹90, Yearly ₹46.
   - Gross margin per payer at **light / typical / heavy** use: Monthly **97% / 80% / 47%**, Yearly **94% / 61% / 29%**. Heavy use stays positive at every cost point because of the allowance table in §3.4.
   - Expected blended ARPPU is about **₹82 gross / ₹57 net per month**.
5. **Expect low conversion.** RevenueCat's 2026 median download-to-paid within 35 days for **IN/SEA users is 0.7%**, against 2.8% in North America; the IN/SEA top quartile is 1.9% [1]. The base case uses 0.8%.
   - Base 12-month net contribution is about **₹4.4 lakh**, with month-12 run-rate about **₹84k/mo**, of which about ₹40k is B2B.
   - Conservative is about ₹0.7 lakh; optimistic about ₹18 lakh.
   - The backend breaks even at about **70–180 paying users** (₹3–8k/mo fixed costs). That is around month 3–6 in the base case on subscriptions alone, and earlier with ads.
6. **Beyond subscriptions, ranked by revenue ÷ effort:**
   1. A **"Studio" prosumer tier** for interior designers and carpenters (₹399/mo, ₹2,999/yr).
   2. The **B2B "does it fit?" widget**: the biggest absolute revenue and still the real business, but sales-heavy.
   3. **Affiliate "buy what fits" links**: easy, but they must stop when B2B deals start.
   4. **One-time paid reports**: small.
7. **Top risks:**
   1. Cloud-credit cost overrun and refund abuse.
   2. India's low and episodic conversion.
   3. The DPDP Act for uploaded room imagery.

   Play policy on paywalls and ads in AR flows is manageable with the placement rules in §5.1.

---

## 1. Free vs Pro feature split

**Principles:**
- **Accuracy is never a Pro feature.** If Pro measured "better", every Free review would say the app is inaccurate.
- **Never take a result hostage.** A measurement or scan the user has made is always visible, placeable and shareable. Pro gates *new capacity* and *richer output*, not data the user already has.
- **Check gates before a task starts, never mid-AR.** A paywall never appears inside a live camera session (§5.1).
- **Free must be complete for the core job:** measure the space, scan or enter the item, place it, get the verdict, share it on WhatsApp.

| Feature | **Free** | **Pro** | Why there |
|---|---|---|---|
| Measure (all modes), ±, quality badge, calibration, history | **Unlimited** | Same | Reputation. Paywalling it would poison reviews and the B2B pitch |
| Smart Measure (auto rectangles, hands-free drop, object naming) | **Included** | + **batch mode**: measure many items in a row, auto-named, exported as a list/CSV | Auto-rectangle *reduces user error*, so it is an accuracy feature and stays free. Batch output is a prosumer convenience |
| Object scan (measured proxy) and **Place in room + Fits/Tight/Too big** | **Included** | Same | This is the hero and the demand engine for B2B and affiliate. Gating it would kill the funnel |
| **Ads** | Capped interstitial after Save→Done; banners only on History/Gallery/Settings; never on camera screens (`business.md` §2.3, `ui-ux.md` §5) | **None** | Ad-free is the #1 reason utility users pay |
| **Gallery size** (saved objects) | **20 items**; later each rewarded ad gives +5, up to 40. Over the cap: view, place, share and delete still work; only *new saves* are blocked, checked **before** a scan starts, with a visible "18 / 20" counter | **Unlimited** | Scans cost us nothing to store (≤ 0.5 MB each, on-device), so the cap is purely a conversion lever. 20 covers a typical home project; heavy users are the ones who pay |
| **Exports** | Share image with dimensions overlay at standard resolution, with a small "Measured with SpaceView" watermark (a WhatsApp growth loop). Rewarded ad (v1.x) = one PDF | **PDF with dimensions** (multi-item, notes, ± shown), **hi-res unwatermarked** image, CSV, **GLB/USDZ** 3D export | Output is what designers, carpenters and shoppers pay for. The watermark is free marketing |
| **Room / floor plan** (v1.x) | **1 room**, view and share as an image | **Unlimited rooms**, PDF floor plan with dimensions, saved projects | One room proves value; renovators and designers need many |
| **Furniture templates** (sofa, bed, wardrobe from guided measurements) | **3 core templates** (straight sofa, bed, wardrobe), full accuracy, saved within the gallery cap | **Full library**: L-sofa, sectional, recliner, bunk bed, TV unit, dining set, modular wardrobe with door-swing clearance; colours and materials | Core templates make the free fit-check honest. The long tail is a nice-to-have |
| **On-device rough 3D scan** (v1.x) | **5 3D scans in total** (user decision 2026-11-23; saved ones count toward the gallery) | **Unlimited** + GLB export | Zero marginal cost, so meter it for conversion only |
| **Cloud photo-real 3D scan** (v1.x) | **No free scans.** Can buy **credit packs**. Sample cloud scans visible in the app | **Included monthly credits** (§3) + packs | Each scan costs real GPU money (₹5–40), and ad revenue (~₹0.13–0.18 per rewarded view [est.]) cannot fund one |
| **Multi-item compare** (2+ items against one space, side by side) | Compare **2** items, not saved | **Unlimited**, saved comparisons, shareable | "Which of these 3 sofas fits?" is a strong purchase-moment upsell |
| **Cloud backup / sync** (v1.x) | **Local backup file** export and import (zip), free forever | **Automatic cloud backup**, restore on a new phone | A free local file keeps data portable (good for reviews and DPDP). Automatic sync costs us storage and an account system |
| Units, EN/HI, accessibility, WhatsApp share | Included | Included | Table stakes |

**Pro value at v1 launch, before cloud features exist:** no ads, unlimited gallery, PDF / hi-res / GLB exports, full template library (if templates ship in v1, otherwise at v1.x), unlimited compare, and Smart batch mode. That is enough to sell an ad-free, output-oriented Pro. Cloud scans and backup arrive as **added value** in v1.x, which also gives a renewal story.

---

## 2. Pricing for India

### 2.1 Recommended price points

| Product (Play type) | Price | Net (GST-registered) | Net (pre-GST) | Notes |
|---|---|---|---|---|
| **Pro Monthly** (auto-renew base plan) | **₹129** | ₹90.0 | ₹109.7 | No trial on monthly. Monthly is the price anchor |
| **Pro Yearly** (auto-renew base plan) | **₹799** (≈ ₹67/mo, "48% off") | ₹557 (₹46.4/mo) | ₹679 | **7-day free trial**, new customers only |
| **Pro 30-day Pass** (prepaid base plan, no auto-renew) | **₹149** | ₹103.9 | ₹126.7 | For the episodic "I'm buying furniture now" user. UPI-friendly; can be topped up [9] |
| **Pro Lifetime** (one-time, non-consumable), v1.x | **₹1,999** | ₹1,394 | ₹1,699 | All on-device Pro features **for life; no included cloud credits** (buy packs). Launch only once churn data exists, around month 4–6, ideally as a win-back or exit offer |
| **Credit packs** (consumable), v1.x | **3 for ₹99**, **10 for ₹299** (base case) | ₹23.0 / ₹20.9 per credit | ₹28.1 / ₹25.4 | Sold to Free and Pro. See §3 for the cost-dependent table |
| **Studio** (separate subscription, prosumer), v1.x | **₹399/mo**, **₹2,999/yr** | ₹278 / ₹174 per mo | ₹339 / ₹212 | Designers and carpenters: branded PDF, client projects, DXF, 10 or 8 credits/mo (§4) |

**Why ₹129 / ₹799 rather than ₹99 / ₹599:**
- The scope has grown. Pro now covers templates, 3D and cloud features, not just ad removal.
- At ₹599/yr the net is only **₹35/mo** after Play and GST, which leaves no room for even one ₹15 cloud scan a month at a healthy margin.
- Indian anchors are higher:
  - Spotify Premium Individual was cut to **₹139/mo** in May 2026 [20].
  - Google One 100 GB is about ₹130/mo [21].
  - YouTube Premium Lite is ₹89/mo [22].
  - RevenueCat's IN/SEA median subscription price is **$3.75/mo and $18.32/yr** (≈ ₹330 and ₹1,610) [1]. SpaceView would still sit well below the median.
- **Keep ₹99 / ₹599 as the A/B "low arm".** Run it as separate base plans served by Remote Config to new installs. Switch if it lifts paying users by at least 35%, since 129 ÷ 99 ≈ 1.30 plus a margin for cloud costs [est.].

**Regional pricing:**
- Set INR prices by hand; do not use Play's auto-conversion from USD. Play's INR price range is **₹4–₹30,000** [10].
- Use psychological endings (₹129 / ₹149 / ₹799).
- For other markets, start from Play's suggested local prices, but keep the US at about $2.99/mo and $19.99/yr. That is still well under Polycam, magicplan and AR Ruler (§5.4) and keeps the Indian price from looking like an arbitrage.
- **UPI Autopay** has worked for Play subscriptions in India since November 2022 [8], so no extra work is needed.

**Trial length:**
- RevenueCat (Aug 2025–Jul 2026) finds utilities convert best with **10–16-day trials (47.4%)** [2]. Trials of 17–32 days convert at 42.5% against 25.5% for trials of four days or fewer, across all apps [3].
- SpaceView's job is **episodic**: a user measures a wall, buys a TV, done. A long trial lets the whole job finish free.
- **Recommendation:** start with **7 days on Yearly only**, and A/B test 7 vs 14 days once 1,000 or more trials have started.
- The trial includes **1 cloud credit**, not the full allowance, so trial farming has bounded cost.
- Play enforces offer eligibility per Google account via the developer's eligibility rule ("new customer acquisition"). Set it to *never had this subscription* [17].

### 2.2 Google Play service fees, as they apply to SpaceView (verified 5 Nov 2026)

Google's official fee page [4] now has **two schedules**.

| | **India (all "remaining markets"), today** | **EEA / UK / US** (from 30 Jun 2026) and **AU / JP** (from 30 Sep 2026) |
|---|---|---|
| Auto-renewing subscriptions | **15%** from day one, regardless of revenue | 10% service fee + 5% billing fee = **15%** |
| One-time products (credit packs, lifetime) | **15% on the first $1M/yr**, *only if enrolled in the 15% programme*, otherwise 30% | **20% + 5% = 25%** for new installs; **25% + 5% = 30%** for existing installs (lower in the Apps Experience programme) |
| Alternative / user-choice billing | **Fee reduced by 4 percentage points** (subscriptions 11%) [4][5] | Separate external-offers schemes |

**What this means for SpaceView:**
1. **Enrol in the 15% service-fee programme in Play Console before the first sale.** Otherwise one-time products are charged at 30%.
2. **Subscriptions cost 15% under either schedule, so put most value in the subscription**, including cloud credits. Google says the new schedule will roll out globally but gives **no India date** [4]. If India moves, credit packs would go from 15% to 25–30%. That is why §3 prices packs with headroom and favours included credits.
3. **User-choice billing (India) is not worth it for v1.** It saves 4 percentage points, but you then pay your own payment gateway (≈ 2% [est.]), integrate a second checkout, and receive service-fee invoices from Google [5][19]. Developer bodies also called the residual fee "still high" [5]. Revisit only above about ₹5 lakh/mo of in-app revenue.
4. **Tax:**
   - Play prices **must include all taxes**.
   - **Indian developers are responsible for their own GST**, and must give Google a GSTIN if registered [6].
   - Google withholds **1% TDS** (with PAN) and, where applicable, **GST TCS** from Indian developers' proceeds [7].
   - Model net = price × 85% − price × 18/118 ≈ **69.75% of list once GST-registered**, and 85% before. B2B invoices will force registration early, so plan on the 69.75%. **Confirm the GST registration threshold and the TCS treatment with a CA.**
5. **Play Billing Library 8.0+ has been mandatory for app updates since 31 Aug 2026** (extensions ended 1 Nov 2026) [18]. Build Pro on **PBL 9**.

---

## 3. Cloud-scan credits: the model that keeps heavy users profitable

### 3.1 Design

**Definitions:**
- **1 credit = 1 standard cloud scan.** A standard scan is a single object or furniture piece, within an image and video cap.
- A **Room or HQ scan = 3 credits**.
- The credits-per-scan table is **published in the app and fixed at the time of purchase**. Play's subscriptions policy forbids misleading users or reducing the recurring value after purchase [11].

**Included credits are granted monthly, even on Yearly.** Annual plans never receive 12 months of credits up front, which limits refund-and-spend abuse (§5.2).

**Rollover:**
- Unused included credits roll over up to **2× the monthly allowance**, then stop accruing.
- They lapse when the subscription ends; this is disclosed on the paywall.
- **Purchased pack credits never expire.** They are a paid good and expiring them invites complaints.

**Charging rules:**
- A credit is charged only when a job **succeeds**. Failed jobs refund the credit automatically.
- One free re-run is allowed per scan if quality checks fail.

**Free users:** no included credits. They can buy packs, and they see sample cloud scans.

**Trial:** 1 credit.

### 3.2 Base-case allowances (₹15 per standard scan)

| Plan | Included credits | Rollover cap | Max cloud cost per month |
|---|---|---|---|
| Pro Monthly ₹129 | **3 / month** | 6 | ₹45 |
| Pro Yearly ₹799 | **2 / month** (24/yr) | 4 | ₹30 |
| Pro 30-day Pass ₹149 | **3** | — | ₹45 |
| Studio ₹399/mo · ₹2,999/yr | **10 / month · 8 / month** | 20 · 16 | ₹150 · ₹120 |
| Trial (7 days) | 1 | — | ₹15 |
| Packs (anyone) | 3 for ₹99 · 10 for ₹299 | Never expire | Paid for |

### 3.3 Sensitivity: allowances and pack prices per cost scenario

**Pack pricing rule:** a pack needs a list price of at least `cost ÷ (0.6975 × 0.70)` ≈ **2.05 × cost per credit** to earn a 30% margin after Play's 15% and GST. That works out to ₹10 per credit at a ₹5 scan, ₹31 at ₹15, and ₹82 at ₹40. Friendly price points land at 28–35% at the base cost. Included allowances are set so that a user who uses **every credit** still leaves at least about **25–30% gross margin** on every plan.

| Cost per standard scan | Monthly ₹129 | Yearly ₹799 | Pass ₹149 | Studio mo / yr | Packs |
|---|---|---|---|---|---|
| **₹5** | 5 / mo | 4 / mo | 5 | 25 / 20 | 5 for ₹99 · 15 for ₹249 |
| **₹15 (base)** | 3 / mo | 2 / mo | 3 | 10 / 8 | 3 for ₹99 · 10 for ₹299 |
| **₹25** | 2 / mo | 1 / mo | 2 | 6 / 4 | 2 for ₹99 · 6 for ₹299 |
| **₹40** | 1 / mo | 6 / yr (one every 2 months) | 1 | 4 / 3 | 1 for ₹99 · 4 for ₹349 |

**If the real cost comes in above ₹25:**
- Make cloud scans a **Studio-first** feature.
- Keep a single ₹99 "pay per scan" option for consumers.
- Push the cheaper on-device 3D scan as the Pro default.

KIRI Engine already gives **free, unlimited photo scans** [15], so a paid cloud scan must offer what KIRI does not: **true scale with ±, the fit verdict, and placement in your room**. A bare mesh is not enough.

### 3.4 Rule for the orchestrator once `3d-capture.md` lands

With *c* = the measured all-in cost per standard scan (GPU + storage + egress, at p90, not the median), and `net` = the plan's net revenue per month (Monthly ₹90, Yearly ₹46.4, Pass ₹104, Studio ₹278 / ₹174):
- `allowance ≤ (0.75 × net − 3) / c`, rounded **down** to a friendly number. This keeps a worst-case gross margin of at least 25%.
- Cap the allowance at **5/month on Monthly and 4/month on Yearly** even when scans are cheap, so users are not anchored on near-free cloud scans.
- `pack_price_per_credit ≥ 2.05 × c`, giving about 30% margin.

This rule reproduces the §3.3 table. Re-check every quarter; GPU prices fall.

---

## 4. Unit economics

### 4.1 Benchmarks used

| Metric | Value | Source |
|---|---|---|
| Download→paid within 35 days, **IN/SEA users** | **median 0.7%**, top quartile >1.9% (N. America 2.8%, APAC 2.4%) | RevenueCat SOSA 2026 [1] |
| Download→paid, all apps / low-priced apps | median 2.0% / 1.4% | [1] |
| Download→trial, IN/SEA | 3.0–3.7% median; Utilities (global) 6.5% | [1][2] |
| Trial→paid, IN/SEA | **15.2%** (N. America 34.2%) | [1] |
| Hard paywall vs freemium, trial→paid at day 35 | 10.7% vs 2.1% | [3] |
| Year-1 realised LTV per payer, IN/SEA | **$14 (≈ ₹1,230)**, against a $23 global median | [1] |
| India in-app spend | ≈ $345 m in Q2 2026, +35% YoY | [23] |
| AdMob India eCPM | Banner ~$0.10–0.50; interstitial ~$0.50–2.00; rewarded the highest (one 2026 comparison puts AdMob India at $0.80–1.50 overall). Sources vary by 4× | `business.md` §2.1, [24][25] |
| Ad revenue per 1k DAU (capped plan) | ≈ $54 / month base (₹4.7k) | `business.md` §2.2 |

**Ad yield per Free user:** about ₹3.8 per free DAU per month (80% of DAU actually served). At a 12% DAU/MAU ratio, which suits an episodic utility [est.], that is about **₹0.45 per free MAU per month**.

### 4.2 Gross margin per Pro user per month (GST-registered, Play 15%)

**Usage levels:**
- *Light* = no cloud scans.
- *Typical* = 1 scan a month. Breakage on included allowances is usually 50–70% [est.].
- *Heavy* = the full allowance. Usage above the allowance is paid through packs at ≥ 30% margin, so it adds to profit.

Every case also deducts "Other" (backup storage, verification backend, refunds and support) at **₹3 per payer per month** [est.].

| Cloud cost / scan | Plan (net ₹/mo) | Light | Typical | Heavy (fixed allowance from §3.2) | Heavy (allowance re-tuned per §3.3) |
|---|---|---|---|---|---|
| ₹5 | Monthly (₹90) | ₹87 · 97% | ₹82 · 91% | ₹72 · 80% | (5 credits) ₹62 · 69% |
| ₹5 | Yearly (₹46.4) | ₹43 · 94% | ₹38 · 83% | ₹33 · 72% | (4 credits) ₹23 · 50% |
| **₹15** | **Monthly (₹90)** | **₹87 · 97%** | **₹72 · 80%** | **₹42 · 47%** | same |
| **₹15** | **Yearly (₹46.4)** | **₹43 · 94%** | **₹28 · 61%** | **₹13 · 29%** | same |
| ₹15 | Pass (₹103.9) | ₹101 · 97% | ₹86 · 83% | ₹56 · 54% | same |
| ₹15 | Studio mo (₹278) | ₹275 · 99% | ₹260 · 94% | ₹125 · 45% | same |
| ₹40 | Monthly (₹90) | ₹87 · 97% | ₹47 · 52% | **−₹33 · −37%** | (1 credit) ₹47 · 52% |
| ₹40 | Yearly (₹46.4) | ₹43 · 94% | ₹3 · 7% | **−₹37 · −79%** | (0.5 credit) ₹23 · 50% |

**Takeaway:**
- With a fixed "3 a month" promise, Pro is **loss-making for heavy users at ₹40 per scan**. Re-tuning the allowance per §3.3 keeps every plan at **≥ ~30% worst-case and ≥ 50% typical margin**.
- Pre-GST, all nets are about 22% higher.
- **Blended ARPPU** for the expected mix of payers in stock at month 12 (≈ 20% monthly, 77% yearly, 3% pass) is **≈ ₹82 gross / ₹57 net per month**.
- This is consistent with RevenueCat's IN/SEA year-1 LTV of about ₹1,230 per payer [1].

### 4.3 ARPU (all users)

Base case, month 12:
- Subscriptions ≈ **₹2.1 net per MAU per month** (recognised revenue, not cash).
- Ads ≈ ₹0.45 per free MAU.
- Affiliate ≈ ₹0.30.
- Packs ≈ ₹0.10.
- **Consumer ARPU ≈ ₹3.0 net per MAU per month [est.]**.

For comparison, ads alone yield about ₹0.45, so **one Pro payer is worth about 125 free MAU of ad revenue**. This is why Pro conversion, not ad density, is the lever.

### 4.4 Backend break-even

**Running costs** are about **₹3–8k/month [est.]**:
- Firebase / Cloud Run with scale-to-zero. Cloud Storage costs about $0.026/GB-month after 5 free GB; Firestore has daily free quotas [26].
- Serverless GPU jobs, billed per second with no idle cost when scaled to zero [27].
- Domain and website, error monitoring, and CA/GST compliance once registered.

**One-off costs:**
- Play developer fee: $25.
- A DPDP-grade privacy policy and terms from a lawyer: about ₹15–40k [est.].

**Break-even** at a blended *typical* contribution of about ₹45 per payer per month:

| Fixed cost / month | Payers needed | …or Free MAU from ads alone | Base-case month reached |
|---|---|---|---|
| ₹3,000 | **≈ 70** | ≈ 6,600 | Month 3 |
| ₹5,000 | **≈ 110** | ≈ 11,000 | Month 4 |
| ₹8,000 | **≈ 180** | ≈ 17,600 | Month 6 |

The last column counts subscriptions alone. Adding ads brings it about one month earlier.

Cloud GPU is variable cost and is covered per credit, so it never enters the fixed-cost line. **Founder time is not included.**

---

## 5. Risks

### 5.1 Play policy: subscriptions, paywalls and ads in AR flows

**Subscriptions policy [11] requires:**
- Clear price, billing period and auto-renew disclosure on the paywall. Do not present the Yearly price mainly as a monthly cost.
- Trial terms: length, what converts, when, at what price, and how to cancel.
- A **clearly visible dismiss** on every paywall.
- An easy cancellation path, such as a deep link to the Play Subscription Center.
- No SKU named "Free Trial".
- **Subscriptions must give ongoing value; one-time benefits belong in one-time products.** Credit packs are therefore consumables, and included credits are an ongoing monthly benefit.

**SpaceView paywall placement rules** (extending `ui-ux.md` §5):
1. **No paywall inside a live AR session.** Gates run *before* the session starts (template picker, "3D scan", gallery-full check) or *after* the result is saved (export, compare).
2. **Never block a result already computed.** A Free user who hits a cap still sees and shares their measurement.
3. **Pro users see no ads anywhere**, and no "upgrade" nags appear on camera screens.
4. Show the soft upsell at most once per session, on a natural break (after Save→Done, alternating with the capped interstitial: never both in one exit).

**Ads:** as in `business.md` §2.3 and `ui-ux.md` §5. Play's disruptive-ads rules treat an ad after a tap that *starts an activity* as unexpected [12].

**Accounts:** cloud backup and credits need **sign-in**, which triggers Play's **account-deletion requirement**: an in-app deletion path plus a web deletion link, plus the Data safety deletion questions [13]. Keep Free account-less.

### 5.2 Refunds and abuse of cloud credits

| Abuse vector | Control |
|---|---|
| Buy Yearly, spend credits, refund | Credits are granted **monthly**, so a refund exposes at most one month's grant. Server ledger plus **Real-time Developer Notifications + Voided Purchases API** (refunds, chargebacks, cancellations; 30-day lookback) [14] claw back unspent credits; a negative balance blocks new jobs. Play's user-initiated refund window is 48 hours; after that the developer decides [16]. Publish a refund policy: "pro-rata refunds exclude consumed credits" |
| Trial farming with many Google accounts | Trial gives 1 credit. **Play Integrity** verdict required for cloud jobs. Per-device and per-account job caps |
| Pack refund after use | Consume the purchase **server-side** only after the credit is in the ledger. Voided Purchases API → claw back; on repeated abuse, warn then suspend cloud jobs (Google recommends escalating, consistent enforcement [14]) |
| Oversized or huge uploads to force expensive jobs | Hard caps on images, video length and resolution per credit tier. Reject before upload |
| Re-running the same scan repeatedly | Dedupe by content hash; one free quality re-run only |
| Modded app with fake entitlement | v1 Pro has no marginal cost, so local verification is acceptable at launch. **Cloud jobs always verify the purchase token server-side** |
| Bursty cost spikes | Global daily GPU budget with a queue ("ready in ~10 min") rather than unbounded autoscale |

### 5.3 Privacy and the DPDP Act: uploading room and object imagery

**Timeline:**
- The DPDP Rules were notified on 13–14 Nov 2025.
- Consent-manager provisions start around Nov 2026.
- **Core data-fiduciary duties** (notice, consent, security safeguards, breach notification, rights, deletion) apply from **about 13–14 May 2027** [28][29].
- Cloud scans will launch inside the window and must be compliant at launch.

**Why room imagery matters:** photos of rooms can contain faces, children, documents, addresses and screens, so they are personal data.

**Penalties:** up to **₹250 crore** for failing security safeguards, and up to ₹200 crore for children's-data violations [30].

**Children:** under the DPDP Act, **anyone under 18 is a child**, and processing their data needs **verifiable parental consent** (Rule 10) [30].

**Controls:**
- **On-device by default.** Measure, proxy scan, on-device 3D and the local gallery never leave the phone. Only an explicit "Cloud scan" upload does.
- **A separate, specific consent screen** before the first upload, covering what is uploaded, its purpose, its retention, the processor's location, and how to delete it.
  - Raw images are deleted within **7 days** of a successful job.
  - Only the mesh is kept, and only while it is in the user's cloud gallery.
- **18+ self-declaration** for cloud features, with the Play content rating and target audience set to adults. If minors become a target audience, cloud features need parental-consent flows.
- **Auto-blur faces** on-device before upload, or at least warn: "people visible? retake" [est. feasibility].
- **Processor contracts (DPA)** with the GPU and storage provider. Keep processing in an Indian region where available. Cross-border transfer is allowed unless the government restricts the destination [29].
- **Security:** encryption in transit and at rest, signed upload URLs, least-privilege service accounts, and a breach-notification runbook.
- **Data safety form:** declare photos, user content and account data, list uploads as optional, and give the deletion path [13].

### 5.4 Competitor pricing (verified Nov 2026 where possible)

| Competitor | Model and current price | Source | Implication for SpaceView |
|---|---|---|---|
| **Polycam** | Free: 10 photogrammetry + 3 AI captures, 150 images per capture. **Basic $30/mo or $150/yr**; Business $400/yr per user; Enterprise $1,200/yr per user (3-user minimum). App Store Pro $26.99/mo or $199.99/yr (Apr 2026). Its AI plan's top tier rose from $34 to $100/mo in Sep 2026 | [31][32][33] | Global scanners meter free *captures* and charge $150+/yr. SpaceView at ₹799/yr (≈ $9) is a fraction, but it must not try to win on mesh quality |
| **magicplan** | **Moved to per-project pricing:** 2 free projects; PRO plans start at 10 projects/month; **$40 per extra project**; Flex, 12- and 24-month terms. App Store tiers (Apr 2026): Sketch $12.99, Report $39.99, Estimate $89.99/mo | [34][35] | Professionals accept **usage-metered** pricing, which validates credits and the Studio tier |
| **Moasure** | Hardware: **Moasure 2 PRO $899**, free app, no subscription required. An earlier optional export add-on was $9.99/mo | [36][37] | Pros pay for accuracy. SpaceView's ± honesty is its proxy for that |
| **AR Ruler-style apps** (Grymala) | Weekly-heavy subscriptions after a 3-day trial: listings show $5.99–19.99/week, $6–19.99/mo, $39–89.99/yr; reviews complain about surprise charges | [38] | **Avoid weekly plans and dark patterns.** A trust-first, generous Free tier is the differentiator in reviews |
| **KIRI Engine** | Basic **free, unlimited photo and LiDAR scans and exports** (150 photos / 2 GB per scan); **Pro $79.99/yr** (≈ $6.66/mo); monthly ≈ $17.99 | [15][39] | Free cloud meshes exist, so SpaceView's paid cloud scan must add **scale, ±, fit and placement** |
| Built-ins (Samsung Quick Measure, Apple Measure) | Free | `business.md` §1.4 | Basic measuring is free everywhere, another reason never to paywall it |

### 5.5 Other risks

| Risk | Mitigation |
|---|---|
| **Low and episodic conversion** (IN/SEA median 0.7%) | 30-day Pass, Yearly-first paywall, PDF export at the purchase moment, B2B as the main revenue line |
| Play fee change reaches India (packs from 15% to 25–30%) | Value sits in subscriptions; pack prices carry headroom; re-price packs if announced [4] |
| GST surprise (−15% of gross) | Model at 69.75% net from day one; register when B2B starts; CA engaged |
| Affiliate links conflict with B2B retail partners | Time-box affiliate until the first B2B contract; then switch to partner placements (`business.md` §3) |
| Accuracy disputes ("it said it fits") | ± on every verdict, "Tight" band, ToS "indicative, verify before purchase"; never "exact" |

---

## 6. Monetisation channels beyond subscriptions (ranked by revenue ÷ effort)

| Rank | Channel | 12-month revenue (base [est.]) | Effort | Notes |
|---|---|---|---|---|
| **1** | **Studio prosumer tier**: interior designers, carpenters, modular-kitchen and wardrobe fitters | 30–100 seats by month 12 → **₹8–28k/mo net** (not in the §7 projection, which makes it pure upside) | **Low–medium.** Reuses Pro: add branding (logo on PDF), client projects, DXF/CSV, 10 credits | Indian designer tools cost $29+/mo (Foyr Neo [40]) and magicplan charges per project [34]. ₹399/mo is an easy yes for someone billing clients. Sell via Instagram and designer WhatsApp groups and carpenter associations |
| **2** | **B2B "does it fit?" widget + catalogue** | Base **₹1.2 lakh** in year 1 (2 Starter customers from month 8, a Growth-lite from month 11), heading to ₹1–3 lakh MRR in year 2 (`business.md` §7) | **High.** Web embed, spec-feed→GLB pipeline, sales cycles of 1–3 months (mid-market) to 6–12 months (national) | The largest absolute revenue and the real business. Billed directly with a GST invoice, **no Play fee**. The consumer app is its demo and demand engine |
| **3** | **Affiliate "buy what fits"** (Amazon.in, Flipkart, or aggregators such as Cuelinks / EarnKaro) | ≈ ₹0.30 per MAU per month → **₹25k** in year 1 | **Low.** Deep links from the verdict screen ("sofas that fit 210 × 90 cm") | Rates are low and vary by source: furniture ~6–10%, TVs/large appliances ~1–4%, often capped per order [41][42]. **Must stop or become partner-only when B2B deals start** |
| **4** | **One-time professional reports** ("Room Report PDF" ₹49 for Free users; "Moving inventory with volumes" ₹99) | **₹5–15k** in year 1 | **Low** once Pro PDF exists (one-time consumable) | Monetises one-off users who won't subscribe. Keep it priced above Pro's per-month cost so Pro remains the better deal |
| — | Rewarded ads (unlock 1 PDF, +5 gallery slots, 24 h ad-free) | Included in ads | Low | Opt-in, so policy-safe and with the best eCPM (`ui-ux.md` §5.2) |

---

## 7. Recommended launch sequence and 12-month projection

### 7.1 What ships when

| Release | Free | Pro | Monetisation plumbing |
|---|---|---|---|
| **v1 (production, about Jan 2027, after the 14-day closed test)** | Measure + ± + badge + calibration + history; object scan → gallery (**20**); **place in room + verdict** (M4); Smart Measure (whatever is ready); 3 core templates if templates ship; watermarked share; capped ads | **Monthly ₹129 · Yearly ₹799 (7-day trial) · 30-day Pass ₹149.** No ads, unlimited gallery, PDF + hi-res + GLB export, full templates, compare, batch mode | PBL 9; on-device entitlement cache; paywall per §5.1; 15% programme enrolment; Remote Config price A/B (₹99/₹599 arm); analytics on paywall views, trial starts and conversion |
| **v1.1 (months 2–3)** | Rewarded ads (+5 slots, 1 PDF); local backup file | Room / floor plan (1 free / unlimited Pro); on-device rough 3D (3 free / unlimited Pro) | Sign-in (optional) + account deletion; small verification backend (Cloud Run) |
| **v1.2 (months 3–5)** | Buy credit packs; sample cloud scans | **Cloud 3D scans with monthly credits**; cloud backup | Credit ledger, RTDN, Voided Purchases job, Play Integrity, GPU queue with daily budget, DPDP consent and 7-day raw-image deletion. Allowances set from `3d-capture.md` per §3.4 |
| **v1.3 (months 4–6)** | Affiliate "shop what fits" (time-boxed) | **Studio tier** (₹399 / ₹2,999); **Lifetime ₹1,999** (no credits) as a win-back | B2B widget v1 and design partners (`business.md` §7.2) |

### 7.2 12-month projection (months from production launch, net after Play and GST, cash basis [est.])

**Assumptions:**

| | Conservative | Base | Optimistic |
|---|---|---|---|
| Monthly installs (month 1 → month 12) | 1.5k → 5k | 3k → 12k | 6k → 30k |
| Download→paid | 0.4% | **0.8%** (≈ IN/SEA median [1]) | 1.5% (near the IN/SEA top quartile) |
| Mix of new payers (Monthly / Yearly / Pass) | 35 / 45 / 20 | 30 / 55 / 15 | 30 / 55 / 15 |

All scenarios share these assumptions:
- Monthly-plan churn of 20% a month.
- MAU retention: 55% in the install month, 15% for months 1–3, 6% after that.
- 12% DAU/MAU.
- Ads at ₹3.8 per free DAU-month.
- Cloud cost ₹15 per scan, with 40% use of included credits.
- Packs bought by 0.05 / 0.1 / 0.2% of MAU per month.
- Affiliate from month 4 at ₹150 / 300 / 500 per 1k MAU.
- B2B revenue:
  - Conservative: none.
  - Base: 2 Starter customers from month 8, about ₹40k/mo by month 11.
  - Optimistic: a ₹1 lakh paid pilot in month 7, then about ₹1 lakh/mo.

Studio and Lifetime are **excluded**, so they are upside.

| | Conservative | Base | Optimistic |
|---|---|---|---|
| Cumulative installs | 39k | 90k | 216k |
| Month-12 MAU | 6.0k | 14.1k | 34.6k |
| Month-12 paying users (incl. annual) | ~100 | ~516 | ~2,340 |
| 12-month subscriptions (net) | ₹57.5k | ₹2.91 lakh | ₹13.06 lakh |
| 12-month packs (net) | ₹2k | ₹9k | ₹45k |
| 12-month ads | ₹17.6k | ₹39.6k | ₹91k |
| 12-month affiliate | ₹5k | ₹25k | ₹99k |
| 12-month B2B | ₹0 | ₹1.18 lakh | ₹4.6 lakh |
| − Cloud GPU cost | −₹9k | −₹44k | −₹1.97 lakh |
| **12-month net contribution** | **≈ ₹0.73 lakh** | **≈ ₹4.4 lakh** | **≈ ₹18 lakh** |
| **Month-12 run-rate (net / month)** | **≈ ₹10k** | **≈ ₹84k** (₹44k consumer + ₹40k B2B) | **≈ ₹2.9 lakh** |
| Backend break-even (₹5k/mo fixed; payers × ₹45 + ads) | Month ~9 | Month ~3 | Month 1 |

**Reading the projection:**
- **The consumer app alone is a small business in India.** The base case reaches about ₹44k/mo consumer net by month 12.
- It does cover its own infrastructure early, and it gives the B2B pitch real users and real fit-check data.
- The upside levers, in order:
  1. Conversion: Pass + Yearly paywall at the purchase moment.
  2. The Studio tier.
  3. The first B2B contracts.
- Optimising ad density is not one of them: one payer is worth about 125 free users of ad revenue.

---

## 8. Build spec for Pro (hand-off to Builders)

This is so Pro can be built now, in v1 scope.

**Play Console:**
- One subscription `pro` with base plans:
  - `monthly`: auto-renew P1M, ₹129.
  - `yearly`: auto-renew P1Y, ₹799, with offer `trial7`: 7-day free phase, eligibility "never had this subscription".
  - `pass30`: prepaid P1M, ₹149.
  - Optional A/B base plans: `monthly-99`, `yearly-599`.
- v1.x products:
  - `studio` subscription: `monthly` ₹399, `yearly` ₹2,999.
  - One-time products `credits_3` (₹99) and `credits_10` (₹299) as consumables.
  - `pro_lifetime` (₹1,999), non-consumable.
- Enrol in the **15% service-fee programme**.
- Grace period 7 days and account hold on (defaults are up to 60 days combined [17]).

**Client (`:app` billing module):**
- PBL 9.
- `EntitlementRepository` exposes `StateFlow<Entitlement>` (`FREE`, `PRO`, `PRO_LIFETIME`, `STUDIO`), cached in DataStore and refreshed on start and on `onPurchasesUpdated`.
- Acknowledge purchases within 3 days.
- `AdsManager` checks the entitlement and **never initialises AdMob for Pro**.
- Feature gates are a single `ProGate` with Remote-Config-backed limits (`free_gallery_cap = 20`, `free_templates = 3`, `free_3d_scans = 3`, `free_compare = 2`).

**Paywall screen:**
- Plan cards showing Yearly first with its total price.
- Trial terms in plain words, EN + HI.
- Visible close button.
- "Manage / cancel" deep link.
- Restore purchases.
- Never launched from an AR screen.

**v1.2 server work, needed for credits:**
- Purchase-token verification (Play Developer API).
- RTDN Pub/Sub.
- Daily Voided Purchases sync.
- Credit ledger (Firestore) with idempotent `grantMonthly`, `consume(jobId)`, `refund(jobId)`.
- Play Integrity check on job submit.
- Global daily GPU budget.

**Strings:** all paywall copy must be localised (EN + HI) with INR prices from `ProductDetails`, never hard-coded [11].

---

## Sources

1. RevenueCat, *State of Subscription Apps 2026* (download→paid by region incl. IN/SEA 0.7%; trial→paid; median prices; year-1 LTV): https://www.revenuecat.com/state-of-subscription-apps
2. RevenueCat, *State of Subscription Apps 2026: Utilities* (utilities download→trial 6.5%; 10–16-day trials 47.4%): https://www.revenuecat.com/state-of-subscription-apps-2026-utilities
3. RevenueCat, free-trial length data (17–32 days 42.5% vs ≤4 days 25.5%), and summary of hard paywall 10.7% vs freemium 2.1%: https://www.revenuecat.com/blog/growth/free-trial-length · https://www.buildmvpfast.com/blog/hard-paywall-vs-free-trial-revenuecat-indie-app-2026
4. Google Play Console Help, *Service fees* (new schedule for EEA/UK/US from 30 Jun 2026 and AU/JP from 30 Sep 2026; remaining markets 15% subscriptions, 15% first $1M; India/Korea alternative billing −4 pp): https://support.google.com/googleplay/android-developer/answer/112622?hl=en
5. User choice billing in India (extended to all Indian developers from 26 Apr 2023 after the CCI order; residual fee criticism): https://inc42.com/?p=385742 · https://blog.google/intl/en-in/products/platforms/sustaining-google-play-as-a-valuable-partner-to-indias-app-ecosystem/
6. Google Payments Center, *India GST* (prices must include all taxes; developers in India determine and pay GST themselves; provide GSTIN): https://support.google.com/paymentscenter/answer/7421525 · https://support.google.com/paymentscenter/answer/7161449
7. Google, tax information for India (Google deducts TDS and, where applicable, GST TCS from Indian developers): https://support.google.com/product-documentation/answer/15152449?hl=de
8. Google Play adds UPI Autopay for subscriptions in India (Nov 2022): https://inc42.com/buzz/google-play-introduces-upi-autopay-for-subscription-based-purchases-in-india/amp
9. Google Play prepaid plans for India and emerging markets (top-ups): https://inc42.com/buzz/google-play-introduces-prepaid-app-subscriptions-developers-emerging-markets-india · https://telanganatoday.com/google-play-introduces-prepaid-app-subscriptions-for-indian-developers
10. Google Play Console Help, price ranges by currency (India ₹4–₹30,000): https://support.google.com/googleplay/android-developer/answer/138294
11. Google Play policy, *Subscriptions* (disclosures, trials, dismiss button, cancellation, ongoing value, localisation): https://support.google.com/googleplay/android-developer/answer/9900533?hl=en
12. Google Play policy, *Ads* (disruptive and unexpected ads): https://support.google.com/googleplay/android-developer/answer/9857753 · AdMob interstitial guidance: https://support.google.com/admob/answer/6201362?hl=en
13. Google Play Console Help, *Account deletion requirements* (in-app path + web link; Data safety deletion questions): https://support.google.com/googleplay/android-developer/answer/13327111?hl=en
14. Google Play Developer API, *Voided Purchases API* (refunds, cancellations, chargebacks; 30-day window; RTDN): https://developers.google.com/android-publisher/voided-purchases
15. KIRI Engine pricing (Basic free, unlimited scans and exports; Pro $79.99/yr): https://www.kiriengine.app/pricing
16. Google Play Help, refunds (request through Play within 48 hours, then developer policy): https://support.google.com/googleplay/answer/2479637?hl=en · Play Console refund handling (developer-set policy, refund-and-revoke): https://support.google.com/googleplay/android-developer/answer/2741495?hl=en
17. Grace period and account hold (≤ 60 days combined; enabled by default): https://www.revenuecat.com/guides/google-play-billing/payment-recovery-grace-period-and-account-hold · Google Play, subscribing help: https://support.google.com/googleplay/answer/2476088?hl=en
18. Play Billing Library 8 requirement from 31 Aug 2026 (extension to 1 Nov 2026; PBL 9 recommended): https://foresightmobile.com/blog/google-play-billing-library-8-migration · https://dev.to/mr_manushukla/play-billing-library-8-by-31-august-2026-every-removed-api-and-the-migration-path-58pn
19. Google, *Alternative Billing System (India) terms* (service-fee invoices, tax withholding as e-commerce operator): https://support.google.com/googleplay/android-developer/answer/13323838
20. Spotify cuts India Premium to ₹139/mo (May 2026): https://www.businesstoday.in/technology/story/spotify-cuts-subscription-prices-in-india-discontinues-premium-lite-531507-2026-05-14
21. Google One India pricing (100 GB ≈ ₹130/mo): https://www.smartprix.com/bytes/google-one-plans-in-india-how-to-buy-google-one-cloud-storage/ · https://www.freepressjournal.in/tech/google-one-slashes-prices-on-annual-plans-in-india-what-you-need-to-know
22. YouTube Premium Lite India ₹89/mo: https://www.digit.in/news/apps/youtube-premium-lite-launched-in-india-at-rs-89-benefits-validity-and-more.html
23. India consumer app spend ≈ $345 m in Q2 2026, +35% YoY: https://techcrunch.com/2026/07/31/india-is-starting-to-pay-for-apps-not-just-download-them/ · https://www.techrepublic.com/article/news-apac-india-app-boom-revenue-problem/
24. AdMob India eCPM ranges (AdMob vs InMobi India; Android India glossaries): https://www.appalize.com/blog/monetization/admob-vs-alternatives-maximizing-your-ad-revenue · https://mwm.ai/glossary/banner-ad
25. Developer-reported AdMob India eCPMs: https://community.kodular.io/t/admob-revenue-in-india/119959 · https://community.kodular.io/t/admob-ecpm-getting-low-by-low-everyday-india-0-22/189851
26. Firebase pricing (Cloud Storage 5 GB free then ~$0.026/GB; Firestore daily free quotas): https://firebase.google.cn/pricing?authuser=0 · https://www.activepieces.com/blog/firebase-pricing-2026-plans-costs-and-free-limits.md
27. Cloud Run GPUs (per-second, instance-based billing; scale to zero; no per-request fee): https://docs.cloud.google.com/run/docs/configuring/services/gpu · https://docs.cloud.google.com/run/docs/configuring/jobs/gpu
28. DPDP Rules 2025 notified, 18-month phased rollout: https://yourstory.com/2025/11/digital-personal-data-protection-dpdp-rules-2025-rollout-over-18-months · https://www.tribuneindia.com/news/business/govt-notifies-digital-personal-data-protection-rules-rollout-in-phases-specified-obligations-for-data-fiduciaries
29. DPDP Rules analysis (phases; obligations; cross-border): https://www.mondaq.com/india/data-protection/1708434/operationalising-indias-data-protection-regime-analysing-the-newly-notified-dpdp-rules-2025 · https://uniqus.com/digital-personal-data-protection-act-timelines/
30. DPDP penalties (₹250 cr security safeguards; ₹200 cr children) and children under 18 / Rule 10 parental consent: https://www.storyboard18.com/advertising/dpdp-enforcement-begins-penalties-up-to-%E2%82%B9250-cr-breach-put-entire-digital-ecosystem-on-high-alert-84226.htm · https://ksandk.com/data-protection-and-data-privacy/childrens-data-protection-under-indias-dpdp-rules/
31. Polycam official pricing (Free / Basic $30 mo or $150 yr / Business $400 yr / Enterprise $1,200 yr): https://poly.cam/pricing
32. Polycam App Store in-app prices (Pro $26.99/mo, $199.99/yr; crawled 22 Apr 2026): https://apppricinglab.com/iap/apple/1532482376
33. Polycam AI top plan $34 → $100/mo (6 Sep 2026): https://costbench.com/changelog/polycam-ai-price-increase-2026-09/
34. magicplan official pricing (per-project; 2 free projects; 10 projects/month minimum; $40 per extra project): https://www.magicplan.app/pricing · https://help.magicplan.app/magicplan-pro-subscriptions
35. magicplan App Store tiers (Sketch $12.99, Report $39.99, Estimate $89.99/mo; Apr 2026): https://apppricinglab.com/iap/apple/427424432
36. Moasure 2 PRO ($899, no subscription required): https://www.moasure.com/products/moasure-2-pro
37. Moasure optional export subscription ($9.99/mo, earlier model): https://www.lawnandlandscape.com/news/moasure-one-updates-technology
38. AR Ruler (Grymala) App Store listing (3-day trial; weekly/monthly/annual tiers vary by store): https://apps.apple.com/us/app/-/id1326773975
39. KIRI Engine Pro monthly ≈ $17.99 (third-party): https://aipicks.jp/tool/kiri-engine/pricing
40. Foyr Neo pricing (from $29/mo): https://www.xpay.sh/saas-pricing/foyr-neo · https://pricingsaas.com/companies/foyr
41. Amazon Associates India category rates (furniture ~8%, electronics 1–4%; third-party, verify in dashboard): https://fluxnote.io/guides/amazon-india-affiliate-commission-rates-2026 · https://www.wscubetech.com/blog/affiliate-marketing-amazon/
42. Flipkart affiliate rates (home and furniture ~4–10%, TVs ~1–5%, per-order caps; third-party, verify in dashboard): https://fluxnote.io/guides/flipkart-affiliate-commission-rates-2026 · https://affiliate.flipkart.com/payment-information
