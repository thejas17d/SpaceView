# SpaceView: Business Potential and Monetization Strategy

*Research date: 2 November 2026. Planning phase, no code yet. Prepared for a solo founder or small team in India.*

> **How to read this.** Figures marked **[est.]** are my own working assumptions, not sourced data. Most public AR-commerce statistics come from vendors and are inflated or poorly sourced. Where sources conflict, both are shown. USD to INR conversions use roughly ₹88/$.

---

## 0. Executive summary

1. **The opportunity is real, but "view in your room" alone is commoditised and under-used.** Flipkart (via Scapic, 2021), Pepperfry (via Avataar), Samsung India and Lenskart already ship AR. Baymard's large-scale testing found only **6%** of furniture shoppers proactively used "View in Room", and **87%** of those who saw it chose not to use it. Measuring is also free on phones: Apple Measure and Samsung's built-in Quick Measure both exist. SpaceView's defensible angle is **"measure your space once, then fit-check any product"**: a saved space profile that a retailer's product page can query ("✓ fits your TV wall"). Launching a full AR session every time is not the angle.
2. **Consumer ad revenue will be small.** In India, banner eCPMs run about $0.10–0.50 and interstitials about $0.50–2.00. A realistic estimate is **~$25–150 (₹2k–13k) per month per 1,000 DAU**, with a base case of about **$55 (≈₹5k)**. "One interstitial per scan" is borderline under AdMob policy and harmful to retention. Cap it, and move most monetization to **rewarded ads plus a cheap Pro tier**.
3. **B2B is where the money is, but the big three are mostly locked up.** Reliance Retail owns Fynd/GlamAR and Urban Ladder. Flipkart owns Scapic. Pepperfry works with Avataar. The best first targets are **mid-tier and regional electronics chains, TV and appliance brands, D2C furniture brands, and Shopify-based D2C sellers**.
4. **Best wedge: a parametric "TV and appliance fit check" for electronics.** TVs, fridges, washing machines and ACs are near-boxes. 3D models can be generated from spec-sheet dimensions at almost no cost, with no scanning or artist time. Pitch it as a conversion and AOV (larger screen) uplift tool, not a returns reducer: Croma excludes size from returns, so wrong-size TVs are the customer's loss, not Croma's. Furniture is the returns-reduction story.
5. **The consumer and B2B editions must be separate builds.** The B2B/white-label SDK and web embed must contain **no AdMob SDK at all**, because Google ads can show Amazon or Flipkart to a retailer's own shoppers.
6. **Recommended 12-month path:** Android MVP with a fit-check gallery, then a Pro tier and a web embed, then 3–5 design-partner pilots, then paid pilots with a case study, then SaaS tiers. Year-1 B2B target: **3–6 paying customers, ₹1–3 lakh MRR [est.]**.

---

## 1. Market sizing and evidence

### 1.1 Market size (headline numbers vary widely)

| Metric | Figure | Source / caveat |
|---|---|---|
| India AR market (all uses) | ~US$2.8 bn (2024) → US$49.6 bn (2033) | [IMARC](https://www.imarcgroup.com/india-augmented-reality-market). Analyst report; broad definition |
| India furniture market (total) | ~US$29.3 bn (2025) | [Ken Research](https://www.kenresearch.com/consumer-products-and-retail/home-and-office-furnishings/india-furniture-market-research-report/498-95.html) |
| India furniture and home-décor e-commerce | ~US$7.3 bn (2025) | [Ken Research](https://www.kenresearch.com/industry-reports/india-furniture-and-home-decor-e-commerce-market) |
| Furniture online penetration | ~10–15%; market $31.5 bn (2026) → $45.5 bn (2031) | [StartupFeed](https://startupfeed.in/why-indias-furniture-ecommerce-is-growing-up-wakefits-ipo-pepperfrys-fall-and-what-comes-next/) |
| India interior design market | US$31–37 bn (2025), residential ~60% | [IMARC](https://www.imarcgroup.com/india-interior-design-market), [Research and Markets](https://www.researchandmarkets.com/report/india-interior-design-market) |
| India packers and movers | ~₹90,016 cr FY26 (+14.2%), weakly sourced | [ColorWhistle citing Shifting App](https://colorwhistle.com/packers-movers-industry-statistics/) |
| India smartphones | ~152–153 m units/yr (2025); Apple 9–10% by volume, 28–29% by value | [IDC via GSMArena](https://www.gsmarena.com/indian_smartphone_market_remains_flat_in_2025_signals_structural_changes-news-71575.php), [9to5Mac / Counterpoint](https://9to5mac.com/2026/01/26/apple-expands-iphone-share-in-india-as-overall-smartphone-market-shipments-flatten/) |
| India consumer app spend | Record US$345 m in Q2 2026, +35% YoY; non-gaming 68% of H1 2026 revenue | [TechCrunch, Jul 2026](https://techcrunch.com/2026/07/31/india-is-starting-to-pay-for-apps-not-just-download-them/) |

**Takeaway:** the retail AR market in India is not reliably sized. The useful framing is bottom-up: the number of retailers and brands selling size-sensitive goods online (TVs, large appliances, furniture, mattresses) multiplied by a realistic annual contract value.

### 1.2 Returns and size mismatch

- **Furniture, global:** online furniture return rate is about **22.7%**, against a 19.3% all-category average (NRF and Happy Returns, 2025). Size or space mismatch is cited as driving about **58%** of furniture returns ([Eightx benchmark](https://eightx.co/blog/average-furniture-and-home-return-rate-benchmarks), citing a US analysis). This is **contested**: a Threekit analysis attributes 40% to assembly and delivery issues and about 20% to transit damage.
- **India:** general e-commerce return rates are cited at **15.8–30%**, with festive peaks up to 40%. Size issues account for **over 60%** of stated return reasons in footwear ([Base.com 2026](https://base.com/en-IN/blog/cod-return-rates-by-product-category-in-india-2026-report/), [ClickPost](https://www.clickpost.ai/blog/psychology-behind-customer-return-decisions)). I found **no India-specific furniture size-mismatch statistic**. Get one from pilot partners' reason codes.
- **TVs and electronics, India:** Croma's return policy is replacement-only for defects. It explicitly excludes "heating, colour, **size**, feature-related dissatisfaction" ([Croma policy summary](https://www.zoutons.com/news/croma-return-policy-replacement-refund-2026); [Croma returns page](https://croma.com/cancellation-and-return)). Unboxed or installed TVs are effectively non-returnable. **Implication:** for electronics, the cost of a wrong size falls on the shopper, and shows up as hesitation, delayed purchase or a downsized purchase. The pitch to electronics retailers is therefore **conversion, AOV uplift (customers see that a 65" fits) and fewer pre-delivery cancellations**, not return-rate reduction.

### 1.3 Existing AR "view in your room" offerings in India

| Player | What exists | Source |
|---|---|---|
| **Flipkart** | "Flipkart Camera" / "View in your space", launched 21 Jul 2021, for furniture, large appliances (fridges) and beauty. Several thousand products; powered by **Scapic** (acquired Nov 2020). Coverage was later reported as patchy. | [Flipkart Stories](https://stories.flipkart.com/vision-for-india-flipkart-camera-immersive-shopping/), [afaqs](https://www.afaqs.com/news/mktg/flipkart-app-introduces-ar-capability-flipkart-camera) |
| **Pepperfry** | AR in app ("Pepperfry AR"). Works with **Avataar**, whose case study on "India's largest furniture marketplace" claims +60% PDP-to-cart visits. Ownership change: acquired by TCC Concept at about ₹662 cr. | [Dwello](https://dwello.in/news/how-is-ar-and-vr-revolutionizing-home-decor-in-2024dwello), [Avataar case study](https://www.avataar.ai/blog/success-story-of-indias-largest-furniture-marketplace), [StartupFeed](https://startupfeed.in/why-indias-furniture-ecommerce-is-growing-up-wakefits-ipo-pepperfrys-fall-and-what-comes-next/) |
| **Amazon.in** | "View in Your Room" and Room Decorator exist in the US. I found **no confirmation it is live on Amazon.in**; a 2022 source said it was unavailable in India. Verify on device. | [Channel IAM 2022](https://en.channeliam.com/2022/04/28/amazon-brings-metaverse-to-the-e-commerce-sector/), [Engadget](https://www.engadget.com/amazon-ar-shopping-furniture-home-decor-171005257.html) |
| **Croma / Reliance Digital / Vijay Sales** | **No AR "view in room" found** on product pages. | Search of PDPs |
| **Reliance group** | Owns **Fynd**, whose **GlamAR** (inside PixelBin) sells furniture AR, 3D configurators and photo-to-3D. Live for Vision Express, Sephora and West Elm India storefronts. Reliance also owns **Urban Ladder**. Reliance Digital and Urban Ladder will very likely default to in-house GlamAR. | [GlamAR](https://www.glamar.io/industry/furniture), [Fynd docs](https://documentation.fynd.com/commerce/docs/extensions/available-extensions/glamar.md), [Outlook Business](https://www.outlookbusiness.com/feature/the-reliance-urban-ladder-deal-is-good-news-for-pepperfry-or-is-it-6150) |
| **Samsung India** | 2021 "AR Demo" places The Serif TV and SpaceMax fridge at true scale, with a callback to a nearby retailer. Instagram AR filters for The Frame. | [Samsung Newsroom India](https://news.samsung.com/in/get-a-life-like-virtual-shopping-experience-from-your-home-with-augmented-reality-demo-for-samsungs-flagship-refrigerator-tv) |
| **IKEA India** | Shopping app launched in India in 2021. **Kreativ** (AR room redesign) is confirmed in the US, Australia and Switzerland; no India launch found. | [Ingka](https://www.ingka.com/news/ikea-launches-shopping-app-in-india), [Pickr](https://pickr.com.au/news/2024/ikea-takes-augmented-reality-to-2-0-with-kreativ-ai) |
| **Lenskart** | Category proof-point: **38.6 m virtual try-ons in FY25** (DRHP). Started on Ditto, now proprietary. | [MarkHub24 citing DRHP](https://www.markhub24.com/post/lenskart-s-3d-virtual-try-on-technology-as-a-market-making-strategy-in-indian-eyewear-retail) |
| **Wakefit** | No AR feature found. Listed after its ₹1,289 cr IPO, so it is a possible target. | [StartupFeed](https://startupfeed.in/why-indias-furniture-ecommerce-is-growing-up-wakefits-ipo-pepperfrys-fall-and-what-comes-next/) |

### 1.4 Competitors and AR SaaS vendors

| Vendor | Positioning | Pricing signal | Relevance |
|---|---|---|---|
| **Avataar** (Bengaluru; $45 m Series B 2022, Tiger and Sequoia) | 2D→3D at scale, AR configurators. **Pivoted toward GenAI product video ("Velocity", Jan 2025)** | Enterprise | Incumbent at Pepperfry and Samsung; its 3D focus is now weaker, which opens a gap. [TechCrunch](https://techcrunch.com/2025/01/06/avataar-releases-new-tool-to-create-ai-generated-videos-for-products) |
| **GlamAR (Fynd / Reliance)** | Try-on and furniture AR, photo→3D, Fynd Commerce plugin | Quote-based; tiers by 100/300/500 SKUs and 2k/5k/10k AR views | Strongest Indian competitor; owns the Reliance ecosystem. [GlamAR pricing](https://glamar.io/pricing/3d-model-creation) |
| **Threekit** (US) | Enterprise 3D configurators | Custom; "from $500" on aggregators | Too expensive and heavy for Indian mid-market. [SoftwareSuggest](https://www.softwaresuggest.com/threekit/pricing) |
| **Vertebrae** | Acquired by **Snap** in Jul 2021; now inside Snap AR Enterprise | Enterprise | Not active in India mid-market. [CB Insights](https://www.cbinsights.com/compare/threekit-vs-vertebrae) |
| **Zakeke** (Italy) | Self-serve 3D/AR product customiser for Shopify and WooCommerce | **$59 / $110 / $255 per month** (5 and 25 product caps on lower tiers) | Self-serve price anchor. [PulseSignal](https://getpulsesignal.com/compare/threekit-vs-zakeke), [Fibbl](https://fibbl.com/zakeke-pricing/) |
| **Sketchfab** | 3D hosting with app-free AR | AR from **$129/mo**; models $40–1,000 | Hosting anchor. [Sketchfab](https://sketchfab.com/blogs/enterprise/cost-augmented-reality) |
| **Ikarus3D** (Mohali) | 3D/AR model production studio | Quote; minimum under $1k | Potential **outsourcing partner** for SKU modelling. [DesignRush](https://www.designrush.com/agency/profile/ikarus-3d) |
| **Polycam** | LiDAR/photo scanning | Pro about **$26.99/mo** (~$19 annual); Business $36/user | Consumer scan competitor. [AIPicks 2026](https://aipicks.jp/mag/polycam-guide-2026) |
| **magicplan** | Floor plans and estimating | $9.99 / $29.99 / $89.99 per user per month | Designer and contractor competitor. [Software Advice](https://www.softwareadvice.com.au/software/186154/magicplan) |
| **Built-ins** | Apple Measure, Samsung Quick Measure, Flipkart Camera | Free | Measuring alone is not a business. |

**Evidence of uplift (treat with caution):**
- Shopify claims a **94% conversion lift** for products with 3D. The claim appears in its changelog with no published methodology ([Shopify changelog](https://changelog.shopify.com/posts/shop-adds-3d-and-augmented-reality-ar-previews)).
- Gunner Kennels (Shopify case study) reported +40% order conversion and **−5% returns** ([Shopify](https://www.shopify.com/kr/case-studies/gunner-kennels)).
- Baymard reports low organic use of AR (6%) ([Baymard 2024](https://baymard.com/blog/deprioritize-view-in-room-augmented-reality)).
- **Plan to prove uplift in your own A/B pilots. Do not lead with vendor stats in retailer meetings; their analytics teams discount them.**

---

## 2. Consumer ad revenue: realistic numbers

### 2.1 India eCPM benchmarks (2025–26)

| Format | India eCPM range | Sources |
|---|---|---|
| Banner (Android) | **$0.10–0.50**, some reports up to $1.00. Developer-reported about $0.08 | [mwm.ai](https://mwm.ai/glossary/banner-ad), [Kodular forum](https://community.kodular.io/t/admob-revenue-in-india/119959) |
| Interstitial | **$0.50–2.00**: AnyMind full-screen peak $0.58 (Dec 2024); MonetizeMore about $0.93–0.97; developers about $0.70 | [AnyMind](https://anymindgroup.com/?p=49164), [MonetizeMore](https://www.monetizemore.com/blog/ecpm-insights/), [Kodular](https://community.kodular.io/t/admob-ecpm-getting-low-by-low-everyday-india-0-22/189851) |
| Rewarded | Typically the highest-eCPM format; India roughly 1.5–3× interstitial **[est.]** | [Appodeal Q4 2024](https://appodeal.com/the-mobile-ecpm-report-updated-q4-2024-view) |

### 2.2 Revenue per 1,000 DAU [est.]

Assumptions:
- Indian Android utility app with about 85% fill.
- About 5 banner impressions per DAU per day, assuming banners on non-camera screens only.
- About 1.2 scans per DAU per day.

| Scenario | Banner/day | Interstitial/day | Total/day | **Per month per 1k DAU** |
|---|---|---|---|---|
| Low (banner $0.10, interstitial $0.50, capped 0.6/DAU) | $0.50 | $0.30 | $0.80 | **≈ $24 (₹2.1k)** |
| **Base** (banner $0.25, interstitial $0.90, capped 0.6/DAU) | $1.25 | $0.54 | $1.79 | **≈ $54 (₹4.7k)** |
| Base, "1 interstitial per scan" (1.2/DAU) | $1.25 | $1.08 | $2.33 | ≈ $70 (₹6.2k), before retention loss |
| High (banner $0.50, interstitial $2.00, 1.2/DAU) | $2.50 | $2.40 | $4.90 | ≈ $147 (₹13k) |

So **10,000 DAU ≈ ₹20k–1.3 lakh per month, base about ₹47k**. That is useful pocket money, not a business. The per-scan interstitial adds only about ₹1.5k per 1k DAU per month over a capped approach, and puts retention at risk.

### 2.3 Is "one interstitial per scan" wise? No.

**Policy:**
- AdMob's [disallowed interstitial implementations](https://support.google.com/admob/answer/6201362?hl=en) prohibit interstitials on app load or exit, back-to-back interstitials, and ads that "surprise users" mid-task.
- AdMob also says to place **"no more than one interstitial ad after every two user actions"** and only at **logical breaks**.
- A scan is a multi-step task (calibrate, then tap points, then save). An ad that fires immediately after a scan, before the result is shown, is the kind of unexpected or recurring interstitial Google flags.

**Banners:**
- AdMob says banners must not sit next to interactive buttons, float over content, or sit on screens with continuous interaction ([AdMob guidance](https://support.google.com/admob/answer/6128877)).
- An AR camera view with a shutter or "place point" button is exactly that kind of screen. **"Banners on all pages" must therefore exclude the AR/camera screens.**

**Retention:** evidence is mostly vendor-sourced, but consistently negative.
- One analysis put the D7 retention drop from heavy interstitials at **10–30%**, unsourced ([mwm.ai](https://mwm.ai/glossary/interstitial)).
- A published case found early interstitial exposure cut 14-day LTV by **7–11%** ([Airflux](https://airflux.ai/blog/interstitial-ads-monetization)).
- For a utility whose core action is the scan, penalising the core action is the worst place to put friction.

**Recommended balanced cap:**
- **No interstitials in the first session, or for the first 3 scans.** This protects onboarding and the first "aha".
- After that, at most **1 interstitial per 3 completed scans**, and at most **1 per 3 minutes**, **~4 per day**. Show it only **after the result is saved and displayed**, at the transition back to the gallery, never between capture and result.
- **Banners only on the gallery, list and settings screens**: adaptive anchored, not on the AR camera or the measurement-result overlay.
- **Rewarded ads as the main ad unit:** "Watch an ad to unlock HD export, a PDF, or +5 gallery slots." This is user-initiated, so it carries no policy risk and earns better eCPM.
- Use **Firebase Remote Config and A/B testing** to tune caps against D1/D7 retention ([Firebase tutorial](https://firebase.google.com/docs/tutorials/optimize-ad-frequency)).
- **Pro users get no ads at all.**

---

## 3. Conflict check: ads in a B2B / white-label product

**Problem:** AdMob serves whatever advertiser wins the auction. A Croma-branded AR experience could show an Amazon, Flipkart or Reliance Digital ad, and a Pepperfry embed could show a Wakefit ad. AdMob category and URL blocking reduces this but cannot guarantee it. Ad SDKs also collect device identifiers. Retailer InfoSec and DPDP reviews will flag any third-party ad SDK inside their app or site. The DPDP Rules were notified 13–14 Nov 2025, with main obligations phasing in through about May 2027 ([Mondaq](https://www.mondaq.com/dpdp-act-and-rules-2025-the-2026-compliance-milestones-businesses-cant-afford-to-miss/1830402), [Hogan Lovells](https://ca.hoganlovells.com/en/publications/indias-digital-personal-data-protection-act-2023-brought-into-force-)).

**Proposed edition split:**

| | **SpaceView (consumer app)** | **SpaceView for Business (B2B / white-label)** |
|---|---|---|
| Distribution | Play Store / App Store under SpaceView brand | Retailer's own app (SDK), website (web embed), in-store tablet app |
| Ads | Capped AdMob, rewarded, house ads. **Competitor-sensitive categories blocked** (consumer electronics, furniture retailers) once you have retail partners | **Zero ad SDKs in the binary.** Separate Gradle product flavour or module; enforce in CI with dependency check |
| Branding | SpaceView | Retailer's brand; optional "Powered by SpaceView" (removable on higher tier) |
| Catalog | Generic objects, user scans, generic TV/appliance size templates; later "partner catalogs" | Only that retailer's SKUs |
| Data | SpaceView privacy policy; user-owned gallery | Retailer is data fiduciary and SpaceView is processor (DPA). No cross-retailer data use; on-device processing by default |
| Monetization | Ads + Pro subscription + (later) **retailer-sponsored "shop this size" placements** | Licence / SaaS / per-SKU fees |

**Channel-conflict rule:**
- Once you sign retail partners, the consumer app must stay **retailer-neutral**, or carry **only paid partner placements**. Do not run Amazon or Flipkart affiliate links that compete with B2B customers.
- Affiliate (Amazon Associates, Flipkart affiliate, EarnKaro or Cuelinks aggregators) is fine **only until** B2B deals start. After that, swap to partner-sponsored listings.
- The consumer app's long-term role is a **demand-side network**: users with saved room profiles that retailers pay to reach ("Your TV wall fits up to 65" → see Vijay Sales offers"). That is worth more than AdMob.

---

## 4. B2B models

### 4.1 Product forms

1. **"Fit-check" web embed (lead product).**
   - Built on Google [`<model-viewer>`](https://modelviewer.dev/examples/augmentedreality/) with `ar-modes="webxr scene-viewer quick-look"`. That gives Android Chrome WebXR, Google Scene Viewer and iOS AR Quick Look from one GLB, with USDZ auto-generated for iOS ([Khronos](https://www.khronos.org/blog/view-a-gltf-model-in-ar-on-android-without-leaving-your-browser)).
   - **No app install is needed.** This matters because most retail traffic is mobile web, and app-based AR adoption is low (Baymard).
   - Differentiator: alongside "View in your room", show **"Does it fit? Enter or scan your space"**. A quick ARCore/ARKit or tape-entry measurement of the wall or alcove gives an instant ✓/✗ and a top-down overlay. This beats the low-usage pattern of AR-for-everything.
2. **White-label SDK** (Android first, iOS later) for retailer apps: measure, scan, saved spaces, place. Higher price, longer sales cycle, InfoSec review. Offer it after the web embed proves value.
3. **Parametric 3D generation (core cost advantage).**
   - TVs, monitors, fridges, washing machines, ACs, microwaves, mattresses and wardrobes are mostly boxes with known W×H×D, bezel and stand geometry.
   - Generate GLB/USDZ automatically from the retailer's spec feed (CSV, PIM or Shopify product JSON). Cost per SKU is about ₹0, so thousands of SKUs can go live in days.
   - This is the wedge against Avataar and GlamAR, which charge per modelled SKU.
4. **Per-SKU 3D model creation service** for complex furniture (sofas, chairs, décor). Outsource to Indian studios such as Ikarus3D and mark up, or use photo-to-3D or GenAI tools with human QA. Do not build an art team.
5. **In-store "measure at home, buy in store" kiosk mode.**
   - The customer saves their room profile in the consumer app or web.
   - Store staff at Croma, Vijay Sales or a regional chain pull it up by QR or phone number and show which models fit.
   - This bridges online research and the offline purchase, where most Indian TV sales still close.
6. **Analytics dashboard:** AR and fit-check sessions, SKUs viewed, fit pass/fail, PDP→cart, and size intent ("users looking at 55" whose wall fits 65" → upsell list").

### 4.2 Pricing benchmarks and proposed INR pricing

**Benchmarks:**
- Zakeke: $59–255/mo.
- Sketchfab AR: $129/mo.
- GlamAR: tiers by 100/300/500 SKUs and 2k–10k AR views.
- Custom 3D modelling: $40–1,000+ per SKU (simple $40–200, complex $200–1,000) ([Sketchfab](https://sketchfab.com/blogs/enterprise/cost-augmented-reality)); furniture $50–500+ ([Hitem3D](https://www.hitem3d.ai/ai-faq/what-is-the-cost-of-a-furniture-3d-model-for-marketers)); US AR studios from $350 ([CGIFurniture](https://cgifurniture.com/3d-rendering-guide/ar-modeling-services)).

**Proposed SpaceView pricing [est.]:**

| Tier | Target | Includes | Price |
|---|---|---|---|
| **Starter (self-serve)** | Shopify / D2C brands, small furniture makers | Web embed, up to 100 SKUs, 5k AR/fit sessions/mo, parametric models, "Powered by" badge | **₹4,999–9,999/mo** (₹49k–99k/yr) |
| **Growth** | Regional electronics chains, mid-size furniture D2C | Up to 1,000 SKUs, 50k sessions, analytics, badge removal, catalog feed sync | **₹35,000–75,000/mo** |
| **Enterprise** | National chains, brands (TCL, Hisense, Godrej, Whirlpool) | Unlimited SKUs, SDK, in-store mode, SSO, SLA, DPA, on-prem/VPC option | **₹8–30 lakh/yr**, custom |
| **3D modelling (furniture)** | Any | Simple ₹1,500–4,000; medium ₹4,000–10,000; complex or configurable ₹10,000–25,000 per SKU | Outsourced at roughly 40–50% gross margin |
| **Parametric SKUs (TV / appliance)** | Any | Auto-generated from specs | Included, or ₹50–200 per SKU at scale |
| **Paid pilot** | Any Growth/Enterprise prospect | 8–12 weeks, 50–200 SKUs, A/B test, results report | **₹1–3 lakh**, credited against the annual contract |

### 4.3 How to approach retailers

**Target list, in priority order:**
1. **Regional and mid-tier electronics chains.** Examples: Vijay Sales, Poorvika, Sangeetha, Pai International, Girias, Bajaj Electronics, Aditya Vision (listed), Kohinoor. They have no in-house AR team, they are omnichannel, and the TV fit check is a clear hook.
2. **TV and appliance brands' India D2C sites.** Examples: TCL, Hisense, Xiaomi, Vu, Acer/Indkal, Thomson, Haier, Godrej Appliances. Brands fund demand generation and can supply spec sheets. Samsung India has already proven the idea.
3. **D2C furniture and mattress brands.** Examples: Wakefit (post-IPO, under pressure to cut returns), Durian, Royaloak, HomeTown, Home Centre, Nilkamal, Godrej Interio, Sleepyhead, The Sleep Company.
4. **National chains, later.** Croma (Tata) and IKEA India have long cycles and global vendor relationships. Reliance Digital, Urban Ladder, Flipkart and Pepperfry are **deprioritised** because they have in-house or incumbent solutions.

**Channels:**
- Go direct to Heads of E-commerce or Digital, CMOs and Category Heads (TVs / large appliances).
- Partner with Shopify and WooCommerce agencies.
- Use the Shopify App Store for self-serve.
- Apply to accelerators such as [Flipkart Leap Ahead](https://india.entrepreneur.com/news-and-trends/flipkart-ventures-launches-leap-ahead-40-to-support/494512) (up to $500k; 2025 cohort themes include AI and commerce enablement).
- Show up at retail-tech events (IReC, India Retail Forum).

**Opener:**
- "We'll put a free 'Will this TV fit your wall?' widget on your top 50 TV SKUs in 7 days, generated from your spec sheet, and A/B test it."
- Lead with a working demo on **their own** product page. Use a Chrome extension or proxy mock-up to show it.

**Pilot structure (8–12 weeks):**
1. **Scope:** 1–2 categories, 50–200 top-selling SKUs, mobile web PDPs.
2. **Design:** randomised A/B test, or a holdout, where 50% of PDP traffic sees the fit-check/AR button. Pre-register the KPIs and success thresholds.
3. **KPIs:**
   - Engagement: share of PDP visitors who use AR or fit-check, and session length.
   - **PDP→add-to-cart** and **conversion**, AR users vs control, and treatment arm vs control arm (intent-to-treat).
   - **AOV / screen-size mix** (TVs): share buying the larger size.
   - **Size-related cancellations, returns and installation-day disputes** (furniture, appliances), using the retailer's reason codes.
   - Assisted in-store conversions where in-store mode is used.
   - CSAT/NPS question: "Did this help you choose the right size?"
4. **Success criteria (example) [est.]:** ≥5% relative conversion lift (intent-to-treat), **or** ≥15% fewer size-related returns or cancellations on pilot SKUs, **or** ≥3 pp shift to a larger TV size.
5. **Commercials:** paid pilot fee credited on conversion to an annual contract. Results become a co-branded case study.
6. **Data:** a DPA under DPDP, no PII leaving the retailer's domain, and on-device measurement.

**Expected sales cycle:**
- Mid-tier chains and D2C: 1–3 months.
- National chains and brands: 6–12+ months, with procurement, InfoSec, and legal review of the DPA.

---

## 5. Additional use cases and segments (ranked)

Scores are 1–5 **[est.]**. Potential reflects market size × willingness to pay. Feasibility reflects tech fit, accuracy needs and sales effort for a small team.

| Rank | Segment | Use case | Potential | Feasibility | Notes |
|---|---|---|---|---|---|
| 1 | **Electronics and appliance retail and brands** | TV / appliance fit-check, parametric 3D, in-store mode | 4 | **5** | Near-zero modelling cost; clear demo; gap left by Croma, Vijay Sales and regional chains |
| 2 | **D2C furniture and mattress brands** | View in room + fit check + 3D modelling service | **5** | 3 | Biggest returns pain; needs 3D models (outsourced); incumbents Avataar and GlamAR |
| 3 | **Interior designers and modular kitchen/wardrobe firms** | Room scan → floor plan PDF, client presentation with placed furniture, site-measurement notes | 4 | 4 | Market US$31–37 bn; designers already pay for magicplan and Polycam; India pricing ₹299–999/mo per seat. Positioned as **pre-measurement**, not final cut-lists |
| 4 | **E-commerce sellers and D2C brands** | Measure parcel and product dimensions for listings; 3D for Shopify PDPs | 3 | 4 | Weight-dispute pain is real ([WareIQ](https://wareiq.com/resources/blogs/shipping-weight-discrepancy-in-indian-ecommerce-why-does-it-happen-perspectives-of-various-parties-and-potential-solutions/), [Shiprocket](https://shiprocket.in/blog/effective-weight-dispute-management)), but ±1 cm is needed. Feasible on LiDAR and with a reference object (A4 sheet) on Android; position as "evidence photo with dimensions" |
| 5 | **Packers and movers / relocation platforms** | Room-by-room item inventory → volume (CFT) estimate → truck size and quote | 4 | 3 | ~₹90k cr industry, fragmented; quote disputes common. B2B via platforms (Porter, NoBroker, Housing, Shiftkart). Needs object recognition + volume heuristics |
| 6 | **Real estate and rentals** | Quick floor plans for listings; carpet-area sanity check; virtual staging | 3 | 3 | Brokers have low willingness to pay. Carpet-area claims carry **legal liability** (RERA disputes), so offer it as "indicative only" |
| 7 | **Construction and carpentry** | Site measurements, opening sizes, quantity estimates | 3 | 2 | Trades need mm precision; laser tapes are cheap (~₹1,500–3,000). Fits only as a lead-gen or notes tool |
| 8 | **Insurance claims** | Damage dimensions and evidence capture for property and motor claims | 3 | 1 | Long insurer sales cycles, IRDAI compliance, fraud requirements. Not for year 1 |
| 9 | **Tailoring** | Body measurements | 2 | 1 | A different technology (pose or body-shape estimation, not AR object measurement), poor accuracy, existing specialists. **Drop** |

**Recommended focus:** #1 and #2 for B2B revenue, #3 as the Pro and prosumer subscription, and #4 as a cheap add-on feature. Explore #5 in months 9–12 if a platform partner appears.

---

## 6. Freemium and premium (consumer)

Price anchors:
- Polycam Pro is about $26.99/mo and magicplan $9.99/mo, both US pricing.
- India's music-subscription price anchor is about ₹119/mo (Spotify) ([Exchange4media](https://www.exchange4media.com/media-others-news/only-14-million-indians-pay-for-music-despite-near-universal-streaming-ey-imi-report-156672.html)).
- Indian app spend is rising fast ([TechCrunch](https://techcrunch.com/2026/07/31/india-is-starting-to-pay-for-apps-not-just-download-them/)).

| | **Free** | **Pro** | **Pro for Business** (designers, sellers, movers) |
|---|---|---|---|
| Price [est.] | ₹0 | **₹79–99/mo, ₹499–699/yr**, optional lifetime ₹1,499 | **₹299–499/mo per seat**, ₹2,999–4,999/yr |
| Ads | Capped interstitial + banner (non-camera) + rewarded | **None** | None |
| Measure / scan | Unlimited basic measure | + LiDAR high-accuracy mode, multi-point area and volume, measurement history | + batch parcel mode with dimension-evidence photos |
| AR Gallery | 10–15 saved objects (rewarded ad = +5) | Unlimited + cloud backup and sync | Shared team library |
| Room profiles | 1 room | Unlimited rooms; "fits my space" checks | Client projects |
| Export | Watermarked screenshot | **PDF floor plans**, PNG/CSV, **GLB/USDZ** 3D export, shareable AR web link | **Branded PDF reports**, DXF/SVG plans, client share links, CSV bulk export (seller listings) |
| Other | — | Unit presets (ft/in, cm), annotations | Logo branding, invoice-ready reports, priority support |

**Expectations [est.]:**
- Free-to-paid conversion for Indian utility apps is typically 0.5–2%.
- At 10k MAU, that is roughly 50–200 Pro users, or ₹4k–20k MRR. Still small, so B2B remains primary.
- Use Google Play's India regional pricing and UPI Autopay-friendly subscriptions.
- Offer a 7-day trial on annual plans.

---

## 7. Key risks and a 12-month go-to-market

### 7.1 Risks and mitigations

| Risk | Detail | Mitigation |
|---|---|---|
| **Accuracy liability** | Peer-reviewed tests show iPhone LiDAR reaching **±1 cm on small objects** but **~10 cm errors** on room- or building-scale scans (iPhone 12 Pro evaluation in *Scientific Reports*, as summarised in search results; [ISPRS 2024](https://isprs-archives.copernicus.org/articles/XLVIII-2-W8-2024/431/2024/); [SAE 2021](https://saemobilus.sae.org/articles/bias-repeatability-measurements-3d-scans-made-using-ios-based-lidar-2021-01-0891)). Non-depth ARCore is worse and varies by device. "Exact dimensions" marketing invites disputes when a sofa or TV doesn't fit | Never claim "exact". Show **± tolerance** on every measurement. Build in a fit margin: fit-check flags "tight" within 5 cm. Run a calibration check with an A4 sheet or credit card. Put "indicative, verify before purchase / construction" in the ToS. B2B contracts cap liability. Publish an in-house accuracy benchmark per device class |
| **Device fragmentation** | Only ARCore-certified devices work ([Google device list](https://developers.google.com/ar/devices)). Google says **>88% of active ARCore devices support the Depth API** (May 2026), but low-end Indian phones and non-certified models are excluded. LiDAR is iPhone Pro / iPad Pro only, a small slice of Apple's 9–10% India share | Web embed works without an install, and **tape-entry fit check works on any phone**. Graceful fallback from depth to plane-only to manual entry. Test on the top 20 Indian models (Redmi, Samsung A/M, Vivo, Realme, Oppo). Android-first |
| **Retailer sales cycles** | 6–12+ months at national chains; InfoSec, DPDP, procurement; pilots stalling without an internal champion | Lead with mid-market and D2C (1–3 months). Use paid pilots with pre-agreed success criteria. Self-serve Shopify tier for cash flow. Pick a category-head champion, not IT |
| **Incumbents and in-house teams** | Reliance (Fynd/GlamAR, Urban Ladder), Flipkart (Scapic), Pepperfry (Avataar), Amazon global | Avoid head-on. Compete on **parametric zero-cost SKUs, fit-check UX, price and speed**. Treat Avataar's pivot to video as an opening |
| **Low AR usage** | Baymard: 6% proactive use | Make the **fit verdict** the hero, not AR. Use saved room profiles. Put a "fits ✓" badge on PDPs. Make AR optional |
| **Ad policy / account risk** | AdMob policy strikes can suspend revenue | Conservative caps (section 2.3), no ads on the camera screen, policy review before each release |
| **Privacy (DPDP)** | Room imagery is personal data. Rules phase in through about May 2027 | On-device processing; store geometry, not photos, by default; explicit consent; DPA with retailers; no ad SDK in B2B builds |
| **Solo-founder bandwidth** | AR, cross-platform, B2B sales and 3D production in parallel | Android plus web only for 6 months. Outsource 3D. Make one channel (electronics) the focus. Find a co-founder or sales partner by month 6 |

### 7.2 Recommended 12-month phased GTM

**Phase 1, months 0–3: build the wedge and get proof of accuracy**
- Android consumer MVP (ARCore): measure (with ± tolerance), save object to the AR Gallery, place at true scale, plus **saved room/wall profiles**.
- **Parametric library:** generic TVs 32"–98" (with stand or wall-mount), fridges, washing machines, ACs, standard bed and mattress sizes.
- SEO and content: "Will a 65-inch TV fit my wall?" / "TV size for viewing distance" calculator pages, a web version using `<model-viewer>`, and YouTube Shorts / Instagram Reels demos.
- Ads: capped plan from section 2.3 plus rewarded ads. No Pro tier yet.
- Publish an accuracy benchmark across 10 popular devices.
- **Targets [est.]:** 10–25k installs, D7 retention ≥15%, a working web fit-check demo.

**Phase 2, months 3–6: monetize consumers and recruit design partners**
- Launch Pro (₹99/mo, ₹599/yr): ad-free, unlimited gallery, PDF floor plans, GLB/USDZ export.
- Build the **B2B web embed v1**: spec-feed CSV → parametric GLB → `<model-viewer>` + fit-check, plus an analytics dashboard.
- Recruit **3–5 design partners** (free or low-cost): 1–2 regional electronics chains, 1 TV/appliance brand, 1–2 Shopify D2C furniture or mattress brands.
- List a Shopify app (Starter tier).
- Apply to Flipkart Leap Ahead or similar accelerators.

**Phase 3, months 6–9: paid pilots and case studies**
- Convert design partners into **paid pilots** (₹1–3 lakh) with A/B-tested KPIs (section 4.3).
- Add a furniture 3D modelling service through an outsourced studio partner.
- Launch an iOS app with a LiDAR high-accuracy mode.
- Launch the **Pro for Business** tier for interior designers and sellers.
- Publish 1–2 co-branded case studies.

**Phase 4, months 9–12: scale B2B**
- Launch the Starter, Growth and Enterprise SaaS tiers. Pitch national players (Croma, Vijay Sales, Godrej, Wakefit) using the case studies.
- Release Android SDK v1 for retailer apps; build **in-store mode** for one chain.
- Run an exploratory pilot with one movers platform (volume estimation) if a partner appears.
- In the consumer app, replace generic ads with **partner-sponsored placements** where possible.
- **Year-1 targets [est.]:**
  - 50–100k installs and 5–10k DAU (ads ≈ ₹25–50k/mo).
  - 100–300 Pro subscribers.
  - **3–6 paying B2B customers, ₹1–3 lakh MRR.**
  - At least one national-chain or brand conversation in procurement.

### 7.3 Year-1 revenue mix (base case) [est.]

| Stream | Month-12 run-rate |
|---|---|
| Consumer ads (≈7k DAU) | ₹30–40k/mo |
| Pro + Pro for Business | ₹20–60k/mo |
| B2B SaaS (Starter + Growth) | ₹1–2.5 lakh/mo |
| Pilots + 3D modelling (one-off) | ₹3–8 lakh cumulative |

**Bottom line:**
- The consumer app is the **demo, data and distribution engine**. Ads only cover server costs.
- The business is **B2B fit-check / AR for size-sensitive retail**, won on zero-cost parametric SKUs for electronics and appliances, with furniture modelling and designer subscriptions as the second leg.
