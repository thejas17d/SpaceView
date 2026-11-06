# SpaceView: 3D Capture Research (cloud scan + on-device scan)

Status: research / planning only (no app code). Prepared 2026-11-05 by the Measurement / 3D research agent.
Scope: how SpaceView can turn complex objects (e.g. a sofa) into an **accurate, true-scale 3D model viewable from all angles** on Android. Covers the agreed tiers 2 (on-device scan) and 3 (cloud scan, Pro); tier 1 (furniture templates from guided measurements) is out of scope here.
Inputs: `docs/research/measurement-engine.md` (accuracy model, "measured proxy"), `docs/PLAN.md`, `HANDOFF.md` (M3 box scan + GLB writer exist).

> **Bottom line.** Use **our own pipeline built on permissive open source, not a vendor API**. Run **COLMAP** (BSD, ETH Zürich / UNC + contributors) for structure-from-motion and dense stereo, with **ARCore camera poses captured during the walk-around** to make the model metric. Add **mvs-texturing** (BSD), **meshoptimizer** and **glTF-Transform** (MIT) to produce a ≤ 5 MB GLB for SceneView. Host it on **Modal serverless GPUs (L4)**, with **Cloudflare R2** for storage (zero egress).
>
> - **Cost:** roughly **$0.25–0.60 of compute per scan**, about **$0.45 typical**, with no fixed GPU cost. KIRI Engine's API is $1/scan with a $500 minimum.
> - **Why not a vendor:** no vendor API we found accepts ARCore poses. A vendor model can therefore only be **scaled to match our own AR measurement**, which adds nothing to accuracy.
> - **Use KIRI for benchmarking only:** use the KIRI Engine API during development as a quality benchmark and possible fallback, not as the production dependency.
> - **Long term:** add **gsplat (Apache-2.0) 2D Gaussian Splatting** for photo-real detail and mesh extraction. Move to dedicated or spot GPUs above ~1,500–2,000 scans/month.
>
> **Never use generative image-to-3D (Meshy, Tripo, TRELLIS…) for dimensions.**
>
> **Expected accuracy, sofa** (engineering estimate, to be validated): overall W/D/H to about **±1–2 cm (0.5–1%)** in good conditions. The error is dominated by ARCore's metric-scale bias. Surface detail is about 2–5 mm on textured fabric, and worse or with holes on plain, dark, glossy or leather surfaces.
>
> **Tier 2 on-device** (§7, in the current sprint):
> - **How:** ARCore **raw depth + confidence**, fused into a **dense TSDF grid (1 cm voxels) bounded by the M3 box**, then marching cubes, vertex colours and a GLB. Use a 45–90 s scan.
> - **Cost:** pure Kotlin, so it is JVM-testable, adds ≈ 50–150 KB with no native library, and needs ≈ 12 MB of RAM for a sofa.
> - **Phone support:** **≈ 76% of listed ARCore models (75% for India-relevant brands)** support the Depth API. That is model share, not install share.
> - **Accuracy:** overall dims from fused depth are about **±2–4 cm W/D and ±1–2 cm H** on a sofa (estimate). That is worse than the measurement engine's box, so dims stay from the engine.

---

## 1. TL;DR recommendation: whose tech, and why

| Horizon | Recommendation | Whose tech | Why |
|---|---|---|---|
| **v1 cloud tier (Pro)** | Self-hosted photogrammetry with ARCore pose priors | **COLMAP 4.2** (BSD-3; SfM + PatchMatch MVS + Poisson mesher), **mvs-texturing** (BSD-3), **meshoptimizer** (MIT), **xatlas** (MIT), **glTF-Transform** (MIT). Runs on **Modal** (serverless GPU, per-second billing) + **Cloudflare R2** | (1) **Accuracy**: we can inject ARCore poses (`pose_prior_mapper` / `model_aligner` Sim3) for an *independent* metric scale; no vendor API takes poses. (2) **Cost**: ~$0.45/scan vs $1/scan. (3) **Privacy/control**: photos of users' homes stay in our buckets, deleted after 30 days. (4) **Licences** all permissive, so SaaS use is OK |
| During v1 development | KIRI Engine API as a **benchmark** (2–4 weeks) and emergency fallback | KIRI Innovation (Hongkong) Ltd. | Cheapest mature-looking photo-to-mesh API ($1/scan, OBJ/GLB/USDZ). But it is "in testing", has a $500 minimum recharge, gives no pose input, keeps results 3 days, and has unclear data terms. Fine for comparing quality, not for production |
| **Long term** (≥ ~6 months after cloud launch) | Hybrid photogrammetry + 2DGS. Feed-forward pose/depth models for robustness. Dedicated GPUs at volume; consider an India region | **gsplat** 2DGS (Apache-2.0) + TSDF meshing (Open3D, MIT); **MapAnything-apache** weights / **VGGT-1B-Commercial** for hard captures; RunPod / AWS `ap-south-1` spot / E2E Networks (India) | 2DGS gives better thin structures and photo-real appearance. Feed-forward models rescue low-texture captures. Dedicated L4s cost ~$0.29/scan at 10k scans/month |
| **Never for dimensions** | Generative image-to-3D | Meshy, Tripo, TRELLIS, Hunyuan3D | Geometry is hallucinated and scale is arbitrary. Acceptable only as a *visual* stand-in squeezed into measured W×D×H and labelled "appearance approximate" (optional, later) |

One-line stack: **ARCore keyframes + poses → R2 → Modal L4: COLMAP (pose-prior SfM + PatchMatch + Poisson) → crop by ARCore floor/box → mvs-texturing → meshoptimizer/xatlas → glTF-Transform GLB (≤50k tris, 2048² texture, ≤5 MB) → SceneView.**

---

## 2. Comparison tables

### 2.1 Self-hosted open source: reconstruction engines

| Engine | What it does | Licence (verified from repo LICENSE) | Commercial SaaS OK? | Output / GLB path | Typical time per object (our estimate, ~100 × 1080p images, 1× L4) | Notes |
|---|---|---|---|---|---|---|
| **COLMAP 4.2.x** (ETH Zürich, UNC, contributors) | SfM (incremental, pose-prior, **global mapper = GLOMAP merged in 4.0**), PatchMatch MVS, Poisson/Delaunay meshing | **BSD-3** ("new BSD"); dependencies licensed separately | **Yes** | PLY mesh → texture with mvs-texturing → GLB | SfM 2–5 min + dense 8–20 min + mesh 1–3 min ≈ **12–30 min** | Most mature. Native support for pose priors and Sim3 geo-registration (`model_aligner`). CUDA pycolmap wheels. 4.1 added the Caspar GPU bundle adjuster |
| **GLOMAP** (standalone repo) | Global SfM | BSD-3 | Yes | Feeds COLMAP MVS | SfM in 1–3 min | Now maintained inside COLMAP 4.x |
| **OpenMVS** | Densify, mesh, refine, **texture** | **AGPL-3.0** | Usable, but **AGPL §13 network clause** applies if we *modify* it and users interact with it remotely | Best texturing/refinement of the OSS tools; exports OBJ/PLY/GLTF | 5–15 min | Run unmodified as a separate process, or replace with COLMAP MVS + mvs-texturing. Get legal sign-off before shipping it |
| **mvs-texturing** (TU Darmstadt) | Texture atlas from calibrated images | **BSD-3** | Yes | OBJ + atlas → GLB | 1–3 min | Pairs with COLMAP's mesh |
| **Meshroom / AliceVision** | Full photogrammetry pipeline (node graph) | **MPL-2.0** | Yes (file-level copyleft) | OBJ → GLB | 20–60 min (CUDA required for depth maps) | Good, but heavier and slower to automate headless than COLMAP |
| **Original 3DGS** (Inria / MPII `graphdeco-inria/gaussian-splatting`) | Gaussian splatting | **Gaussian-Splatting License: non-commercial, research/evaluation only**; "cannot use, exploit or distribute … for commercial purposes without prior and explicit consent" | **No** (Inria commercial licence needed) | n/a | n/a | **Do not use**, including derived repos (below) |
| **gsplat** (nerfstudio) | 3DGS/2DGS rasterisation & training library; has `rasterization_2dgs` + `simple_trainer_2dgs.py` | **Apache-2.0** | **Yes** | Splat PLY; mesh via rendered depth → TSDF (Open3D) → marching cubes | 2DGS ≈ 10–15 min training (DTU 2DGS ≈ 11 min reported) + 1–3 min TSDF | Up to 4× less memory and 15% faster than Inria (vendor claim). Best permissive base for splats |
| **Nerfstudio / Splatfacto** | Training framework on gsplat; `ns-export tsdf/poisson` | **Apache-2.0** | Yes | Mesh export built in | similar | Less active (last push Jul 2025) than gsplat |
| **NVIDIA 3DGRUT** (3DGRT/3DGUT) | Ray-traced / unscented-transform splats | **Apache-2.0** | Yes | USD/USDZ/PLY; mesh not primary | — | Handles distorted cameras. The repo itself recommends gsplat for production |
| **Brush** | Splat training/viewing in Rust/wgpu; **runs on Android** | **Apache-2.0** | Yes | Splat PLY only (no mesh export) | — | Candidate for on-device splats (§7) |
| **2DGS (official), SuGaR, GOF, RaDe-GS, MILo** | Splat → mesh methods (current SOTA family) | **Inria Gaussian-Splatting License (non-commercial)** in every repo | **No** | — | — | Re-implement the *idea* on gsplat (2DGS already in gsplat) rather than reuse this code |
| **PGSR** (ZJU) | Planar-based splats → mesh | "educational, research and non-profit purposes only … prohibited for commercial use" | **No** | — | — | |
| **OpenSplat** | Splat trainer | **AGPL-3.0** (despite Splatfacto origins) | Caution | — | — | |
| **Instant-NGP** (NVIDIA) | NeRF | NVIDIA Source Code License: "only … non-commercially" | **No** | — | — | |
| **MASt3R / DUSt3R** (Naver) | Feed-forward matching/reconstruction | **CC BY-NC-SA 4.0** | **No** | — | — | |
| **VGGT** (Meta) | Feed-forward poses/depth/points | VGGT License v1: code commercial-friendly; **only the `VGGT-1B-Commercial` checkpoint** may be used commercially (not military) | Yes, with that checkpoint | Poses/depth → COLMAP format | seconds | Useful for hard, low-texture captures |
| **MapAnything** (Meta) | Feed-forward metric reconstruction; accepts intrinsics/poses/depth as inputs | Code Apache-2.0; weights **`map-anything-apache` = Apache-2.0**, `map-anything` = CC-BY-NC | Yes, with the apache weights | `demo_colmap.py` exports COLMAP | seconds–minutes | Natural fit for ARCore pose + depth inputs. Long-term candidate |
| **Depth Anything V2 / 3** | Mono / multi-view depth | V2: **Small = Apache-2.0**, Base/Large/Giant = CC-BY-NC-4.0. DA3: SMALL/BASE/METRIC-LARGE/MONO-LARGE = Apache-2.0; LARGE/GIANT/NESTED = CC BY-NC 4.0 | Check per checkpoint | — | — | Weight licences differ from code licences |
| **Open3D** | TSDF, meshing, ICP, simplification | **MIT** | Yes | — | TSDF + MC 1–2 min | Server-side TSDF for 2DGS depth fusion |
| **meshoptimizer / gltfpack**, **glTF-Transform**, **xatlas**, **Draco**, **KTX-Software**, **trimesh** | Simplify, UV-atlas, compress, write GLB | MIT, MIT, MIT, Apache-2.0, Apache-2.0, MIT | Yes | GLB | < 1 min | **PyMeshLab is GPL-3.0**: avoid it in anything we distribute |

**Getting a GLB that SceneView/Filament renders well on a mid-range phone** (targets; Scene Viewer's guidance in brackets):
- **Geometry:** ≤ 50k triangles for a sofa. Scene Viewer recommends ≤ 100k, with 30–50k "ideal". Use quadric simplification with border and silhouette preservation, then re-bake detail into the texture (optionally a 1024² normal map).
- **Texture:** one 2048 × 2048 base-colour atlas, which is Scene Viewer's max texture size. JPEG/WebP ≈ 1–1.5 MB.
- **File size:** ≤ 5 MB. Scene Viewer recommends ≤ 10 MB and its validator errors above 15 MB. With 50k tris (quantised, ≈ 0.4–1.6 MB) plus the 2048² JPEG, expect **≈ 2–3.5 MB**.
- **Extensions:** Filament's gltfio lists `KHR_draco_mesh_compression`, `KHR_mesh_quantization`, `KHR_texture_basisu` and `EXT_meshopt_compression` as supported. **Verify on device that SceneView's bundled Filament 1.72.1 Android artifact includes the Draco/BasisU decoders** before relying on them. Scene Viewer lists only `KHR_materials_unlit` / `KHR_texture_transform`, so the **WhatsApp/"share" copy must be a plain GLB** (no Draco/meshopt/KTX2).
- **Conventions:** units in metres, +Y up, origin at the centre of the floor footprint. Store dims, ± and provenance in `extras` (the same canonical record as the M3 proxy).

### 2.2 Third-party capture / reconstruction APIs

| Vendor | Type | Pricing per scan | Commercial / white-label | Data location & privacy | Output | Processing time | API maturity | Keeps / trains on data? |
|---|---|---|---|---|---|---|---|---|
| **KIRI Engine API** (KIRI Innovation (Hongkong) Ltd.) | Photogrammetry ("Photo Scan"), "Featureless Object Scan", 3DGS (+ to-mesh) | **1 credit = $1 per call**, any scan type; **min recharge 500 credits ($500)**; free trial credits (page says 10; a mirror says 20). "No additional fees" | API user agreement exists but its text was not readable. The app EULA grants KIRI an "unrestricted, unlimited, irrevocable, perpetual … royalty-free" licence to user "Contributions". **White-label terms not found** (unverified) | **Not stated** in the privacy policy; licensor entity is Hong Kong. API models are **deleted after 3 days**. No pose/scale input | obj, fbx, stl, ply, **glb**, gltf, usdz, xyz. Texture 1K–8K. Auto-masking. 20–300 images or video ≤ 1080p / 3 min | "A few minutes … to 20–30 minutes", plus a queue | **"API is in testing"; credit prices "subject to change"**. REST + webhooks + status polling | Retention 3 days (models). Training use **not stated**: ask in writing |
| **Luma AI** | Was NeRF/3DGS capture | No current capture-API price found. A $1/capture figure is undated and third-party | — | — | — | — | **Capture uploads reported disabled since Nov 2024; Flythroughs/Genie sunset 1 Jan 2026.** Company pivoted to generative video | **Not viable** |
| **Polycam** | Consumer/business capture apps | **No public reconstruction API found.** Enterprise plan mentions "custom integrations" | — | — | — | — | No public API | **Not viable** for us |
| **Epic RealityScan 2.x** (ex-RealityCapture) desktop | Pro photogrammetry (Windows) | Free under $1M revenue; **$1,250/seat/yr** above that | **EULA §1.2(d) forbids making it "available to third parties on a software-as-a-service, hosted service, time-sharing, service bureau or similar basis"** | n/a | — | — | — | **Cannot be our backend** without custom terms from Epic |
| **Niantic Spatial Scaniverse** | Capture app; cloud meshes/splats; NSDK 4.0 (Android) | Credit plans: Free / $20 / $50 per month / Custom. Enterprise lists "Custom API integration" | Enterprise negotiated | On-device processing stays local; cloud is credit-based | FBX mesh, PLY/SPZ splats | — | No public per-call reconstruction API found | Unverified |
| **Autodesk Reality Capture API** (APS) | Cloud photogrammetry, up to 300 object images, textured OBJ | **Price not verified** (APS token model) | Autodesk terms | Autodesk cloud (US) | OBJ etc. | — | REST. Still referenced by 2026 client libraries; no deprecation notice found | Unverified |
| **Agisoft Metashape Pro** (self-run commercial software) | Photogrammetry with Python API | **$3,499 node-locked** (perpetual); floating packs ≈ $21k | Licence terms for cloud/headless SaaS use **not verified** | Our infra | OBJ/GLB etc. | ~10–30 min | Mature | n/a |
| **NVIDIA** | No hosted capture API found. OSS: 3DGRUT (Apache-2.0), Instant-NGP (non-commercial) | — | — | — | — | — | — | — |
| **Meshy** (generative) | Image / multi-image → 3D | Multi-image to 3D: **20 credits** (mesh) / **30** (2K–4K texture) / 35 (8K). Pro **$20/mo incl. 1,000 credits**, so ≈ **$0.60 per textured model** (our arithmetic) | Paid plans own outputs; free = CC BY 4.0 | Not verified | GLB etc. | ~1 min | Mature | Training terms **not verified** |
| **Tripo** (generative) | Image → 3D | ~30–50 credits; Tripo's blog says ~$1 per 100 credits, so **≈ $0.30–0.50 per model** (third-party/vendor-blog figures, conflicting) | Paid = commercial | Not verified | GLB etc. | < 1 min | Mature | Not verified |
| **Self-hosted generative** | TRELLIS / TRELLIS.2 (**MIT**); Hunyuan3D 2.1 (community licence, **excludes EU, UK, South Korea**) | GPU time only | — | Ours | GLB | < 1 min | — | — |

**Generative is not measurement.** Meshy, Tripo, TRELLIS and Hunyuan3D infer unseen geometry and output arbitrary scale. Their dimensions are guesses, which violates the product's #1 rule. The only acceptable use is a *visual* mesh non-uniformly fitted into the **measured** W×D×H box, flagged "shape approximate, dimensions measured". That is a tier-1/2 visual upgrade, not tier 3.

**DPDP Act 2023 / DPDP Rules 2025.** The Rules were notified 13/14 Nov 2025. Consent Manager provisions apply from Nov 2026, and **most core obligations from ~13 May 2027**: notice, security safeguards, processor contracts, breach reporting and penalties.
- **Does it apply?** Yes. Photos of a user's home can contain faces, family photos, documents or addresses, and they are linked to an account/device. That makes them personal data.
- **Who is responsible?** We are the **Data Fiduciary**, and any API vendor or GPU host is our **Data Processor**. We must contractually ensure the processor uses reasonable safeguards (Rule 6), and erase data when the purpose ends (s.8(7)).
- **Cross-border transfer:** allowed unless the Government restricts a country (s.16). No such list has been notified yet.
- **What this means for us:**
  1. Use a vendor only with a DPA-style contract and a clear no-training statement.
  2. Delete raw uploads after processing (30 days max) and offer "delete my scan".
  3. Blur or skip frames with faces (optional).
  4. Prefer storage we control, with an **India region option** (R2 jurisdiction or AWS/GCP Mumbai) as an easy upgrade.
  5. Update the Play Data safety form (photos uploaded, purpose, deletion).

### 2.3 Hosting options for the self-hosted pipeline

| Host | GPU price (list) | Billing / idle | Cold start & queueing | Storage / egress | Fit |
|---|---|---|---|---|---|
| **Modal** | L4 **$0.000222/s (≈ $0.80/h)**; A10 $1.10/h; L40S $1.95/h; A100-80 $2.50/h; H100 $3.95/h. CPU $0.0000131/core/s; RAM $0.00000222/GiB/s | Per second, **scale to zero**. Starter plan $0 with **$30/month free credits**; Team $250/mo | Container cold start of seconds to tens of seconds (GPU snapshots cut LLM cold starts to ~5–20 s). Irrelevant against a 15–25 min job. Autoscaling queue | Volumes $0.09/GiB-mo; egress $0.04/GiB. Region pinning costs 1.15–1.75× | **Best for v1**: zero fixed cost, Python-native, easy CUDA images |
| **RunPod Serverless** | L4 **$0.75/h**; RTX 4090 $1.34/h; L40S $1.75/h; A100 $2.72/h (CPU/RAM included) | Per second, scale to zero ("flex") | Flex workers cold-start; "active" workers cost more | Network volume $0.07/GB-mo; egress not stated | Cheapest serverless; good v1 alternative |
| **RunPod Pods** (dedicated) | L4 **$0.44/h** (community) / $0.59/h (secure); RTX 4090 $0.34/h; L40S $0.79/h | Hourly, always on | None (warm) | as above | **Best at volume** (≥ ~1,500 scans/mo) |
| **Replicate** | L40S **$3.51/h**; A100-80 $5.04/h; T4 $0.81/h | **Private models bill setup + idle time** | Model boot billed | — | Poor fit (≈ 2–3× cost) |
| **Lambda Cloud** | A10 **$1.29/h**; A6000 $1.09/h; A100-40 $1.99/h (prices effective 16 Nov 2026) | Hourly instances, not serverless | None once up | Egress not stated | Dev box / batch, not per-scan serverless |
| **AWS `g6.xlarge`** (1× L4, Mumbai `ap-south-1`) | On-demand **≈ $0.966/h**; spot seen at **$0.39–0.60/h** (snapshots vary) | Hourly / per second (Linux) | Spot interruptions: needs checkpointing and a queue (SQS) | S3 + egress (rates not verified here) | India data residency + spot at volume |
| **GCP `g2-standard-8`** (1× L4, Mumbai `asia-south1`) | On-demand **≈ $0.89/h**; spot price not verified (third-party figures conflict) | Per second | Spot preemption | GCS + egress | Same as AWS |
| **E2E Networks** (India, MeitY-empanelled) | L4 ≈ **$0.57/h**, L40S ≈ $1.20/h (aggregator, Oct 2026; INR prices exclude 18% GST) | Hourly | — | India | India-resident option at volume |
| **Cloudflare R2** (storage, all options) | **$0.015/GB-mo**, Class A $4.50/M, Class B $0.36/M, **egress free**. Free tier 10 GB-mo, 1M A, 10M B | — | — | **Zero egress** | Uploads + results bucket |

---

## 3. Cost-per-scan math

**Assumptions** (engineering estimates, to be replaced by measurements from the first 50 real scans):
- **Capture package:** ~100 keyframes at 1920×1080 JPEG (q≈90, ~400 KB) plus poses, intrinsics and low-res depth, giving a **≈ 45 MB upload**. Uploads to R2 are free (ingress). The raw package is kept 30 days.
- **Result:** GLB + JSON **≈ 5 MB**, kept indefinitely.
- **Compute:** **20 min wall time per scan on 1× L4** (plausible range 12–30 min), with 4 physical CPU cores and 24 GiB RAM in the same container. Add **+15% for retries and failed reconstructions.**
- **Fixed overhead:** ~$10/mo for an API, queue and monitoring (e.g. Cloudflare Workers paid plan + logs).
- **Storage:** priced at steady state after 12 months of results.
- **Egress:** result downloads are ~5 MB, served from R2 with zero egress. Modal→R2 writes are 5 MB × $0.04/GiB ≈ $0.0002, which is negligible.

Per-scan compute:
- **Modal L4:** (0.000222 + 4 × 0.0000131 + 24 × 0.00000222) $/s × 1,200 s = **$0.39** (range $0.24–0.59 for 12–30 min).
- **RunPod Serverless L4:** $0.75/h × 20 min = **$0.25**.
- **Replicate L40S**, assuming 12 min: **$0.70**, plus idle billing.
- **Dedicated RunPod L4 pod** at $0.44/h: one pod handles about 3 scans/h × 720 h × 60% utilisation ≈ 1,300 scans/month, which is $317 of pod time per month.

**Cost per scan per month (USD, including storage, fixed overhead and +15% retries):**

| Option | 100 scans/mo | 1,000 scans/mo | 10,000 scans/mo | Notes |
|---|---|---|---|---|
| **KIRI Engine API** | **$1.00** ($100; but **$500 minimum prepay**) | **$1.00** ($1,000) | **$1.00** ($10,000; volume price unknown) | Plus our own R2 storage, since KIRI deletes results after 3 days. No pose input → scale only by fitting to our AR box |
| **Self-host on Modal (L4, serverless)** | **$0.55** gross ($55); **≈ $0.25** after the $30/mo free credit | **$0.46** ($460) | **$0.45** ($4,520) | Zero fixed GPU cost; simplest ops |
| **Self-host on RunPod Serverless (L4)** | **$0.39** ($39) | **$0.30** ($300) | **$0.29** ($2,900) | Cheapest serverless list price |
| **Self-host on dedicated L4 pods** (RunPod community) | $3.27 (1 idle pod, $327) | **$0.33** (1 pod) | **$0.29** (9 pods, $2,880) | Needs a queue. Wins only once pods stay > 50% busy. AWS Mumbai spot (≈ $0.39–0.60/h) is similar, with India residency |
| Optional 2DGS photo-real pass (gsplat, +10–15 min L4) | +$0.20–0.30 | +$0.20–0.30 | +$0.15–0.25 | Long-term add-on |

Storage is small. R2 costs ≈ $0 at 100 scans/mo (inside the free tier), ≈ $1.4/mo at 1,000 and ≈ $17/mo at 10,000 (12 months of results + 30 days of raw).

**Unit-economics warning.** Pro is planned at ₹99/mo (≈ $1.04 at ≈ ₹95/USD) or ₹599/yr. Two or three cloud scans a month would consume most of a monthly subscription before Play's fee. **Cap cloud scans** at, for example, 2/month in Pro, and sell extra-scan packs (e.g. ₹49/scan). Alternatively, make the cloud scan a separate in-app purchase. Business research should own the final pricing.

---

## 4. Licensing pitfalls

1. **Inria 3DGS licence contaminates most splat→mesh repos.**
   - The original 3DGS repo, **2DGS, SuGaR, GOF, RaDe-GS and MILo** all ship the Gaussian-Splatting License: research/evaluation only, and "THE USER CANNOT USE, EXPLOIT OR DISTRIBUTE THE SOFTWARE FOR COMMERCIAL PURPOSES WITHOUT PRIOR AND EXPLICIT CONSENT". PGSR is likewise non-commercial.
   - **Rule:** never copy code from these repos. Implement methods on **gsplat (Apache-2.0)**, which already has a 2DGS rasteriser, using only paper descriptions. CUDA kernels copied from Inria-derived repos are the most common accidental contamination.
2. **Model weights ≠ code licence.**
   - VGGT: only `VGGT-1B-Commercial` is commercial.
   - MapAnything: only the `-apache` weights.
   - Depth Anything V2: only Small is Apache.
   - DA3: Small/Base/Metric-Large/Mono-Large are Apache; Large/Giant/Nested are CC BY-NC.
   - MASt3R/DUSt3R: CC BY-NC-SA.
   - **Pin the exact checkpoint** in code and in a `THIRD_PARTY.md`.
3. **AGPL tools (OpenMVS, OpenSplat).** Running unmodified binaries server-side is generally considered fine, but modifying them triggers the AGPL §13 obligation to offer the source to network users. Keep them out of the default pipeline, or isolate them as unmodified CLI steps after legal review. **Never link AGPL code into the Android app.**
4. **GPL (PyMeshLab / MeshLab)** is fine for internal scripts but must not be shipped. Prefer meshoptimizer, Open3D, trimesh and xatlas.
5. **RealityScan / RealityCapture is free < $1M revenue, but the EULA forbids hosted / service-bureau use.** It cannot be our cloud backend without a custom Epic agreement.
6. **NVIDIA Instant-NGP is non-commercial.** 3DGRUT is Apache-2.0.
7. **Vendor API terms.**
   - KIRI's app EULA takes a perpetual, transferable licence to user "Contributions", and the API agreement wasn't readable. Get, **in writing**, no training, deletion on request, a processing location, and white-label permission before any production use.
   - Meshy and Tripo training clauses were not verified.
8. **Hunyuan3D 2.1** community licence is territory-limited (excludes the EU, UK and South Korea) and has use restrictions. **TRELLIS** is MIT.
9. **COLMAP** is BSD, but its LICENSE warns that "building COLMAP with these dependencies may affect the resulting COLMAP license". Build with default (permissive) deps, and record them.

---

## 5. Accuracy approach (making the cloud model true-scale)

### 5.1 Principle
Photogrammetry alone is **scale-free**. Scale must come from (a) ARCore's metric VIO, and/or (b) a known length. Tier 3 must produce dimensions that are **independent of, and cross-checked against**, our existing AR measurement. Otherwise it adds nothing to accuracy.

### 5.2 What to capture on the phone (per keyframe)
- **Image:** a 1920×1080 frame. Options:
  - GPU camera texture readback (ARCore's GPU stream is typically 1920×1080).
  - Shared-camera Camera2 YUV stream. Mid-tier phones can run "two YUV CPU streams plus one GPU stream" or "one YUV CPU stream plus one GPU stream", so this is device-dependent.
  - The default CPU image is only 640×480, which is too low for photogrammetry.
- `Camera.getPose()` (world-from-camera), **intrinsics matching the captured image** (texture intrinsics for a GPU readback, image intrinsics for the CPU image), timestamp, tracking state.
  - Remember the M2 blocker: the focal length must be in the *captured image's* pixels.
  - Convert ARCore's OpenGL camera frame (−Z forward, +Y up) to COLMAP's (+Z forward, +Y down).
- **Raw depth + confidence** if the Depth API is available. Typically ~160×120, up to 640×480 on some devices. Confidence is 0–255, and it is 0 on textureless pixels.
- **Scene-level data:** the ARCore **floor plane**, the M3 **box outline** (W×D×H ± from the measurement engine) as ROI and cross-check, and any **known-length calibration** factor from M2 (scoped to this session).

### 5.3 Server-side scale and alignment
1. **Run SfM using ARCore positions as priors.** Use COLMAP `pose_prior_mapper` (prior std ≈ 3–5 cm, robust to outlier priors since 3.13). Alternatively, run free SfM (`global_mapper`) and then **`model_aligner`**. That estimates a **Sim3 (scale + rotation + translation) with RANSAC** from ≥ 3 camera centres, with a required `alignment_max_error`.
2. **Accept the scale only if:**
   - ≥ 70% of cameras are registered;
   - the Sim3 inlier ratio is ≥ 80%;
   - the residual RMS is ≤ 3 cm;
   - the per-segment scale (first half vs second half of the walk) agrees within 1% (this detects VIO drift or relocalisation jumps).
3. **Apply the session's known-length calibration factor** if the user calibrated (M2 feature, band 0.9–1.1).
4. **Cross-check against the M3 box measurement.** Compute the gravity-aligned oriented bounding box: crop above the ARCore floor plane + 5 mm, take yaw from the footprint's min-area rectangle, and compare W/D/H with the AR box. If any dimension disagrees by more than max(2 cm, 2%), show "Check" and the AR value.
   - Caveat: both come from the **same VIO session**, so this catches gross failures but **not a common-mode scale bias**. Only a known-length reference catches that.
5. **Optional "precision" reference** (later): an A4 sheet on the floor next to the sofa. Its 4 corners are triangulated from many views, giving a scale ≈ 0.2–0.5% (estimate). This is independent of VIO. It ties in with the existing calibration UX.

### 5.4 Realistic accuracy for a sofa (≈ 2.0 × 0.9 × 0.85 m)
These are engineering estimates combining the measurement-engine error model (ARCore VIO scale ≈ 1% 1σ) with typical MVS behaviour. **To be validated with the test kit** (add a sofa or armchair plus a cardboard box to the `measurement-engine.md` §6.3 set).

| Quantity | Expected (good light, textured fabric, sofa pulled ≥ 40 cm from the wall) | Dominant error |
|---|---|---|
| Overall W / D / H | **±1–2 cm (0.5–1%)** without a reference; **±0.5–1 cm** with known-length/A4 | ARCore metric-scale bias (systematic, not averaged out by many poses) |
| Local surface (cushions, arms) | 2–5 mm RMS on textured fabric; 5–15 mm plus holes on plain/dark/velvet/leather | MVS matching on low texture and specular surfaces |
| Thin parts (legs < 3 cm, piping, tassels) | Often lost or swollen | MVS resolution at 1–1.5 m |
| Back against wall / underside | Not captured → filled flat | Coverage |

The UI must show the model's dims with their own ± and a provenance label ("measured by 3D scan, scale from AR (+ calibration)").

---

## 6. Capture UX on Android

- **Keyframes, not video.** Auto-capture keyframes from the AR session, gated on the following:
  - tracking `TRACKING`;
  - angular velocity < ~0.3 rad/s (as in the measure mode);
  - sharpness (variance of Laplacian) above threshold;
  - **≥ ~10 cm translation or ≥ ~6° rotation** since the last keyframe.

  Video is easier, but a 90 s 1080p clip at ~8–10 Mbps is ~100 MB, is motion-blurred and has no per-frame poses unless we log them anyway. KIRI does accept video ≤ 1080p / 3 min if we ever use it.
- **Frame count:** 80–120 for a sofa, 40–60 for a chair. KIRI's API accepts 20–300 images, a useful sanity range.
- **Coverage guidance:**
  1. Start by marking the floor box (the existing M3 BOX flow). This gives the ROI, the orbit centre and the cross-check dims.
  2. Then show a **coverage dome** around the box with 3 rings: low (~40 cm, slightly up), mid (seat height) and high (~1.5 m, looking down ~45°). Each has 24–36 segments that fill green as keyframes land, with ≥ 60–70% overlap.
  3. Suggested distance: 1–1.5 m. Show a "move closer / step back" hint as in measure mode.
  4. Ask the user to **pull the sofa from the wall** if possible, and to clear cushions and throws off the floor around it.
- **Lighting:** prefer diffuse daylight or all room lights on. Avoid strong window backlight and moving shadows, and use no flash or torch. Warn below ~100 lux (ARCore light estimate). Lock exposure/white balance if using the shared camera, to keep textures consistent.
- **Material hints:** warn on glossy leather, glass tables and black or white plain covers ("results may have holes").
- **Upload:**
  - **Size:** JPEG q90 at 1920×1080 ≈ 300–500 KB, so ~30–50 MB per sofa. Depth adds 160×120×2 B ≈ 38 KB/frame, or ~4 MB.
  - **Transport:** use **resumable multipart upload to R2 presigned URLs** via WorkManager, with a "Wi-Fi only" default and a mobile-data toggle that shows the size.
  - **Device storage:** keep the package on the device until the server confirms. Delete it after the result arrives (with an opt-in "keep for re-processing").
  - **Results:** deliver via FCM push or polling. Expected turnaround is 15–30 min.
- **Privacy UX:** tell the user the photos leave the device, are used only to build their model, and are deleted after 30 days. Offer face blurring (later).

---

## 7. On-device option (tier 2) — expanded for the current build sprint

**Recommendation.** Build "Quick 3D" in **pure Kotlin**, with no new dependencies:
- **Input:** ARCore **raw depth + confidence** (Depth API).
- **Volume:** a **dense TSDF grid bounded by the M3 box ROI** (box + 10 cm margin), with **1 cm voxels** and a 4–5 cm truncation band. Fuse **pixel-driven** at ≤ 5 Hz on a worker thread.
- **Meshing:** **marching cubes** at the end, then vertex-clustering decimation to ≤ 25k triangles with vertex colours, written as GLB by the existing `GlbWriter` (extended).
- **Scan:** a 45–90 s walk-around.
- **Code placement:** the maths (TSDF, marching cubes, decimation) lives in `:engine` (JVM-testable); frame sampling lives in `:ar`.
- **Size:** adds ≈ 50–150 KB of dex, no native library, and so no new 16 KB-alignment risk.
- **Dimensions:** stay with the measurement engine. The fused mesh is an *appearance* model and a cross-check, roughly ±2–4 cm on a 2 m sofa (estimate).

### 7.1 Approaches on mid-range Android (no ToF)

| Approach | Feasible? | Expected quality | Verdict |
|---|---|---|---|
| **ARCore raw depth + TSDF + marching cubes** | **Yes**, on Depth-API phones (§7.2) | Smooth, "blobby" closed mesh. Random noise averaged out, but edges rounded and slightly inflated, and holes where confidence is 0 (plain fabric, black leather, glass). Vertex colours from the 640×480 CPU image | **Build this** |
| Mono depth network scaled by ARCore depth (Depth Anything V2 Small — Apache; DA3-Small/Base — Apache) + TSDF | Possible: the 2026 "Lantern" hackathon design does it with ExecuTorch on a Qualcomm NPU, but its cleanup runs in Blender on a host | Denser and fills plain surfaces, but adds network bias. Scale from ARCore | Later. Weights are tens of MB, which needs Play Feature Delivery and NPU work |
| On-device Gaussian splatting (Brush — Apache-2.0, runs natively on Android; Scaniverse does on-device splats on Android, ≥ 4 GB RAM) | Yes, on strong phones | Photo-real appearance; **not a mesh**; SceneView/Filament has no splat renderer that we found | Not this sprint |
| On-device photogrammetry | No (KIRI: needs "high-end computing power", not on its roadmap) | — | No |

### 7.2 Phone support: what share of ARCore phones support the Depth API

We parsed Google's ARCore supported-devices page on 2026-11-05. The "Supports Depth API" note appears in the device notes column:

| Slice of Google's list | Models listed | With Depth API | Share |
|---|---|---|---|
| All listed ARCore models | 1,816 | 1,386 | **76%** |
| India-relevant brands (Samsung, Xiaomi incl. Redmi/POCO, vivo, OPPO, OnePlus, realme, Motorola, Nothing, Tecno, Infinix, itel, Google) | 836 | 626 | **75%** |
| Samsung Galaxy A/M/F series | 96 | 46 | **48%**. Older A-series and budget models such as **Galaxy A13 5G / A14 5G** lack it |
| Motorola moto g | 73 | 47 | 64% |
| realme | 118 | 78 | 66%. realme 5/6/7/8 Pro lack it |
| Xiaomi "Redmi" models | 48 | 29 | 60%. Redmi Note 7/8 lack it; Note 10/11+ mostly have it |
| Hardware ToF (any brand) | — | 11 | Irrelevant in India today |

**Interpretation:**
- These are **model counts, not installed-base share**. No public figure exists for India.
- Models without depth skew old (2017–2021) and budget. Most ≥ ₹12–15k phones from 2022 onward list Depth API support (see `measurement-engine.md` §5).
- **Working estimate (to verify):** about 65–80% of *active* ARCore phones among our users will support depth.
- **Measure it from day 1:** log `Session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)` alongside the existing `checkAvailability()` telemetry, and check the Play Console device catalogue.
- Hide Quick 3D (never grey it out with a fake result) on unsupported phones, and offer the measured-proxy box instead.

### 7.3 Libraries and approaches for TSDF fusion + meshing on Android

| Option | Licence (verified) | Android / APK impact | Pure Kotlin/JVM? | Verdict |
|---|---|---|---|---|
| **Own TSDF + marching cubes + vertex clustering in Kotlin** (in `:engine`) | Ours | **≈ 50–150 KB dex** (estimate). No `.so`, so no 16 KB page-size risk | **Yes**: see the budget below | **Recommended** |
| Same algorithms in C++ via NDK | Ours | + ≈ 100–300 KB per ABI (estimate). Must pass the existing 16 KB alignment CI check | n/a | Fallback only if profiling shows Kotlin too slow |
| **meshoptimizer** (simplification) | **MIT** | Small C++ library, adds an NDK build | No | Optional later, for quality decimation |
| **Open3D** (`ScalableTSDFVolume`, marching cubes) | **MIT** | **No official Android build found**: ARM64 support is "experimental" on Linux/macOS. Large dependency tree. Forum users report pose-convention problems with ARCore inputs | No | Use server-side only (2DGS path) |
| **voxblox** (ETH ASL) | **BSD-3** | ROS/catkin build; heavy | No | Read for ideas (merged ray integration, block hashing) |
| **VDBFusion** + **OpenVDB** | MIT + Apache-2.0 | OpenVDB pulls in TBB/Boost: several MB | No | No |
| **nvblox** (NVIDIA) | Apache-2.0 | **CUDA only**, so not on phones | No | No |
| **InfiniTAM** (Oxford) | "**solely for non-commercial use**" | — | — | **Licence-blocked** |
| **ElasticFusion / Kintinuous** (Imperial) | "**non-commercial … academic research purposes**" | — | — | **Licence-blocked** |
| **BundleFusion** | **CC BY-NC-SA 4.0** | — | — | **Licence-blocked** |
| **3D Live Scanner** source (L. Vonásek; 500k+ installs) | Repo **Apache-2.0**, but `third_party/` holds differently-licensed code (OpenCV, Poisson, Delaunay, **Tango 3D reconstruction**) | Reference for a shipped ARCore/AREngine on-device scanner | Mixed C++/Java | **Study only.** Do not copy `third_party/` code |

**Pure-Kotlin compute budget** (engineering estimate, mid-range phone, one background thread):
- **Raw depth:** ~160×120 (19.2k px) per frame. Not every ARCore frame carries new raw depth, so compare timestamps.
- **Integration (pixel-driven):** each valid pixel updates the voxels on its ray within ±truncation. That is ~8–10 voxels at 1 cm / ±4–5 cm, so ≈ 0.2M voxel updates per frame, roughly **5–15 ms per frame** in ART-compiled Kotlin with primitive arrays (no boxing). At 5 Hz that is < 10% of one core.
- **Projective per-voxel integration over the whole grid** would cost 2.4M projections per frame (~0.3 s). **Avoid it**, or restrict it to blocks inside the frustum.
- **Marching cubes:** one pass over 2.4M cells takes ~0.1–0.3 s at the end. A downsampled preview mesh every ~2 s is optional.
- **Decimation:** vertex clustering on a 2 cm grid takes tens of ms.

So pure Kotlin is viable. It also keeps the core **unit-testable on the JVM in CI**, using synthetic depth maps of a box or sphere with a known answer, which matches the `:engine` pattern (Kover ≥ 90%).

**Memory (dense ROI grid at 1 cm):**
- A sofa ROI of 2.2 × 1.1 × 1.0 m holds 2.42M voxels.
- Each voxel stores tsdf (`ShortArray`, 2 B) + weight (`ByteArray`, 1 B) + RGB565 (`ShortArray`, 2 B) = 5 B, giving **≈ 12 MB** total.
- At 5 mm the same ROI needs ≈ 97 MB, and gains nothing because depth noise is cm-level.
- Switch to sparse 8³ blocks only for ROIs above ~4 m³ (rooms), which are not in scope.

### 7.4 Real-world quality: what existing Android depth scanners show

There is **no rigorous public benchmark** of Depth-API (non-ToF) TSDF meshes of furniture. The evidence is thin and mostly qualitative:

| Source | What it shows |
|---|---|
| **3D Live Scanner** (Android, 500k+ installs by end-2021; ARCore + Huawei AREngine) | "Performs best on devices with a ToF sensor". It works on any ARCore Depth-API phone, but quality is not quantified |
| **Wound-scanner developer report** (Android, ARCore raw depth) | "The majority of Android devices without depth sensors produced poor results" until they built a **multi-frame raw-depth** pipeline, which "significantly improved accuracy". No numbers. This supports multi-frame fusion over single frames |
| **ISPRS 2022** (on-phone reconstruction with ARCore depth) | ARCore **smooths** its depth maps (edges blurred). The paper notes that depth error grows **quadratically with distance**, citing ARCore documentation. In a realistic scene, depth differed from a Metashape ground truth by **≈ 8 cm**, and results depend on the motion performed (more views → better) |
| **Google Depth API docs** | Most accurate at **0.5–5 m**. **Imprecise on white walls / featureless surfaces**. Valid only after the user moves the phone. Raw depth has "higher accuracy" than full depth, with confidence 0–255 (0 on textureless pixels) |
| **MultiScan** (open research scanner; Android records RGB + ARCore depth + confidence + poses) | Reconstructs **on a server**, not on the phone. Its docs say devices with a dedicated depth sensor give more accurate depth maps |
| **arXiv 2204.01693** | Measures ARCore **SLAM feature points** (not the Depth API) against LiDAR: MAE **23–30 cm** in one scene. *Note: `measurement-engine.md` cites this paper as a Depth API evaluation. It is not one; the orchestrator may want to correct that citation* |
| Scaniverse (Android) | Ships on-device splats (≥ 4 GB RAM). That is not a depth-fusion mesh, but shows sustained on-device 3D processing is acceptable to users |

**Takeaway:** single-frame depth-from-motion is poor. Multi-frame fusion near the object (0.6–1.5 m), with confidence filtering, is what working apps converge on. Our own bench test (§7.6) must produce the numbers.

### 7.5 Recommended settings (starting values; tune on the bench)

| Parameter | Value | Why |
|---|---|---|
| Depth mode | `Config.DepthMode.RAW_DEPTH_ONLY` (fall back to `AUTOMATIC` for preview) | Raw depth is more accurate and comes with per-pixel confidence |
| Confidence threshold | ≥ 128/255 to start; tune 100–200 | Trade fill against noise |
| Depth range used | **0.35–2.0 m** | Error grows ~quadratically with distance. Furniture is scanned at 0.6–1.5 m |
| **Voxel size** | **1.0 cm** default. **1.5 cm** for ROIs > 3 m³ or phones with < 4 GB RAM | Matches the cm-level noise. Below this adds memory, not detail |
| Truncation band | max(4 cm, 3 × σ_depth(z)) | Must exceed depth noise at the working distance |
| Integration weight | ∝ confidence / z², capped (e.g. 64) | Favours near, confident samples. The cap lets later, better views win |
| Fusion rate | ≤ 5 Hz, only frames with **new** raw-depth timestamps, tracking `TRACKING`, angular speed < 0.3 rad/s | Thermal and CPU budget, plus motion-blur rejection (same gate as measure mode) |
| ROI | M3 box outline + 10 cm margin. Carve away everything below **floor plane + 1 cm** | Removes floor and background, and bounds memory |
| **Scan duration** | **45–90 s** target. Minimum 30 s or until ≥ 80% of the coverage dome is filled. Hard cap **120 s** | Depth-from-motion needs motion and multiple views; longer adds drift and heat |
| Coverage | Same 3-ring dome as tier 3 (§6). Show the live preview mesh, coloured by weight | Teaches users where data is missing |
| Thermal guard | Pause fusion at `PowerManager.getCurrentThermalStatus() ≥ THERMAL_STATUS_SEVERE` (API 29+); stop at CRITICAL | Protects the 30 fps AR budget and avoids ANR/crash |
| Output | Marching cubes → vertex clustering to **≤ 25k triangles** → vertex colours → GLB with `KHR_mesh_quantization` (Filament supports it) | ≈ 0.3–0.6 MB, close to the M3 ≤ 0.5 MB/scan gate. Keep a plain-GLB share copy |

### 7.6 Realistic accuracy of overall dimensions from fused depth-from-motion

This is an engineering estimate built from the error sources below. No published benchmark exists, so it must be measured.

| Error source | Effect on a ~2.0 × 0.9 × 0.85 m sofa |
|---|---|
| Random per-frame depth noise (≈ 1.5–3 cm at 1–1.5 m, estimate) | Mostly averaged out by 20–50 fused observations per surface point: ≈ 0.3–0.6 cm |
| **Depth smoothing / edge inflation** at silhouettes (ARCore smooths depth; stereo "foreground fattening") | **+1–3 cm per side** on W and D, so extents read **large** |
| **Missing low-confidence surfaces** (plain or dark fabric, leather sheen) | Extremal faces missing → extents read **small**, or holes |
| ARCore VIO metric scale (≈ 1%, see measurement-engine §1) | ±2 cm on 2 m |
| TSDF iso-surface / voxel quantisation (1 cm) | ±0.5 cm |
| Floor contact / crop at the floor plane | Height ±0.5–1 cm (height is the *best* dimension: the floor plane is a strong reference and the top is well observed) |
| **Overall (good light, textured fabric, 0.6–1.5 m)** | **W/D ≈ ±2–4 cm (≈ 1–2%), H ≈ ±1–2 cm**. On plain, dark or glossy upholstery, ±5 cm or more, and failures |

Compare this with the measurement engine's box measurement: ±1–2 cm for spans of 1–2 m in good conditions.

**Product rule:**
- Show **box dims from the measurement engine**. The mesh is only rescaled if a dimension is user-confirmed.
- Compute mesh extents (gravity-aligned OBB above the floor plane) as a **consistency check**. If they disagree by more than max(3 cm, 3%), say "3D shape is approximate — dimensions are from your measurement".
- **Bench-test protocol addition** (`measurement-engine.md` §6.3): a cardboard box of known size, an armchair and a 2–3-seat sofa, on 2 phones (T1 Depth-API mid-ranger, T0 Pixel), 5 scans each. Report the mesh-extent error against the tape, the hole area, scan time and the phone's thermal state.

### 7.7 Pipeline sketch (where the code goes)
1. **`:ar` `DepthFusionSampler`:**
   - acquire raw depth + confidence and CPU image;
   - gate on tracking, motion and new timestamps;
   - hand over `(depth ShortArray, conf ByteArray, rgb, intrinsics scaled to the depth size, camera pose)` to the engine on a worker thread;
   - release images immediately.
2. **`:engine` `TsdfVolume`** (dense ROI grid), `integrate(frame)`, `MarchingCubes.extract()`, `VertexClusterDecimator`, `MeshExtents.gravityObb(floorPlane)`. All are pure Kotlin with JVM tests on synthetic scenes.
3. **`:engine` `GlbWriter`:** extend it with an indexed mesh, `COLOR_0` and quantised positions. Keep the Khronos validator check in tests.
4. **`:app` Quick 3D screen:**
   - mark the box (existing M3 flow);
   - walk the coverage dome with the live preview;
   - Review (box dims + mesh) → Save to Gallery as a new `ObjectKind`/representation.
5. **Shared capture package (§6):** optionally also save 1080p keyframes + poses, so "Upgrade to cloud (Pro)" needs no re-scan.

---

## 8. Risks

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| 1 | **Scale/accuracy shortfall.** Model dims inherit ARCore's ~1% metric-scale bias, and the cross-check with the AR box is not independent | High (accuracy is product #1) | Sim3 gates (§5.3), apply known-length calibration, optional A4 reference, always show ± + provenance. Validate on the test kit before launch |
| 2 | **Reconstruction failures on real Indian homes**: dark leather, velvet, plain white covers, sofa against a wall, clutter, dim tube-light rooms, people walking through. Expect 10–30% failed or partial scans initially (estimate) | High | Capture gating + coverage dome + material warnings. Server QA (registered-camera %, hole area) → auto-refund the scan credit. Fall back to the tier-2 mesh + measured box |
| 3 | **Unit economics.** ~$0.45/scan (+ user mobile data) vs ₹99/mo Pro | High | Cap scans per month, sell scan packs, run serverless (zero idle), move to dedicated/spot at volume, measure the real GPU minutes |
| 4 | **Licence contamination** (Inria/NC code or NC weights slipping in) | Medium–High | `THIRD_PARTY.md` with pinned versions/checkpoints. CI check for LICENSE files in the server image. Use gsplat, not Inria forks |
| 5 | **Privacy / DPDP** (home photos, cross-border processing) | Medium (obligations bite ~May 2027) | 30-day raw deletion, delete-on-request, processor contracts, India-region option, Data safety form, no vendor without a written no-training clause |
| 6 | **Vendor instability.** KIRI API "in testing" with changeable prices; Luma's capture product faded; no Polycam API | Medium | Self-host as primary; vendors only as benchmark or fallback behind an interface |
| 7 | **High-res frame capture on mid-range phones** (shared camera / GPU readback cost, 30 fps AR budget) | Medium | Capture keyframes at ≤ 2 fps, use PBO async readback, fall back to the best CPU config. Test on T1/T2 tier phones |
| 8 | **Upload size and connectivity** (30–50 MB) | Medium | Resumable WorkManager upload, Wi-Fi-only default, JPEG/WebP tuning, optional server-side frame subsampling |
| 9 | **GLB rendering cost / extension support** in SceneView's Filament build | Low–Medium | ≤ 50k tris, 2048² texture. Verify Draco/BasisU decoders on device; ship plain GLB if absent |
| 10 | **Ops burden for a small team** (GPU images, CUDA versions, queue, monitoring) | Medium | Modal (no infra), one container image, pinned COLMAP 4.2.x, golden-dataset regression run on every image change |
| 11 | **Tier-2 on-device mesh quality** (depth-from-motion smoothing/holes; Depth API on ~75% of listed models, ~48% of Samsung A/M/F models) | Medium | Label as "approximate shape", dims always from the measurement engine, hide on unsupported phones, bench-test before release (§7.6) |

---

## 9. Suggested next steps (not scheduled; for the orchestrator)
1. **Spike, 1 week:** capture 3 test objects (box, chair, sofa) with a debug "record keyframes + poses" mode. Run COLMAP locally or on Modal and in KIRI's free trial. Compare dims against the tape and the AR box.
2. If the spike meets ±2 cm on the sofa, write the cloud-tier plan:
   - backend (R2 + Modal + small API, auth via Play Integrity / Firebase Auth);
   - Pro gating and scan caps;
   - Data safety;
   - DPDP notice.
3. Tier 2 (on-device TSDF) is **in the current sprint**. Build it per §7.5–7.7 in pure Kotlin (`:engine` TSDF/marching cubes/decimation with JVM tests on synthetic depth; `:ar` `DepthFusionSampler`; extend `GlbWriter` with indexed meshes and `COLOR_0`). Then run the §7.6 bench protocol. It shares the capture package with tier 3.

---

## Sources

**Open source and licences** (licence text checked directly from each repository's LICENSE via the GitHub API / raw files, 2026-11-05)
- Inria 3DGS licence (non-commercial clause): https://github.com/graphdeco-inria/gaussian-splatting/blob/main/LICENSE.md
- gsplat (Apache-2.0; `rasterization_2dgs`, `examples/simple_trainer_2dgs.py`): https://github.com/nerfstudio-project/gsplat ; docs https://docs.gsplat.studio/main/ ; JMLR paper https://mlanthology.org/mloss/2025/ye2025jmlr-gsplat
- Nerfstudio (Apache-2.0): https://github.com/nerfstudio-project/nerfstudio ; Splatfacto commercial note https://radiancefields.com/nerfstudio-1-0-and-splatfacto-released
- COLMAP (BSD; changelog 3.13–4.2.1; FAQ on pose priors, `model_aligner`, known poses): https://github.com/colmap/colmap/blob/main/LICENSE.txt ; https://colmap.github.io/changelog.html ; https://colmap.github.io/faq.html
- GLOMAP (BSD-3): https://github.com/colmap/glomap
- OpenMVS (AGPL-3.0): https://github.com/cdcseacave/openMVS/blob/master/COPYRIGHT.md
- mvs-texturing (BSD-3): https://github.com/nmoehrle/mvs-texturing
- Meshroom / AliceVision (MPL-2.0): https://github.com/alicevision/Meshroom/blob/develop/COPYING.md ; https://github.com/alicevision/AliceVision/blob/develop/COPYING.md
- 2DGS: https://github.com/hbb1/2d-gaussian-splatting ; SuGaR: https://github.com/Anttwo/SuGaR ; GOF: https://github.com/autonomousvision/gaussian-opacity-fields ; RaDe-GS: https://github.com/HKUST-SAIL/RaDe-GS ; MILo: https://github.com/Anttwo/MILo (all ship the Inria Gaussian-Splatting License)
- PGSR (non-commercial): https://github.com/zju3dv/PGSR/blob/main/LICENSE.md
- OpenSplat (AGPL-3.0): https://github.com/pierotofy/OpenSplat
- NVIDIA Instant-NGP (non-commercial): https://github.com/NVlabs/instant-ngp/blob/master/LICENSE.txt ; 3DGRUT (Apache-2.0): https://github.com/nv-tlabs/3dgrut ; https://radiancefields.com/3dgrt-and-3dgut-v1-0-update-released-by-nvidia
- Brush (Apache-2.0, Android): https://github.com/ArthurBrussee/brush
- VGGT (commercial checkpoint note): https://github.com/facebookresearch/vggt
- MapAnything (Apache code; apache vs CC-BY-NC weights): https://github.com/facebookresearch/map-anything
- MASt3R (CC BY-NC-SA 4.0): https://github.com/naver/mast3r
- Depth Anything V2 (Small Apache, others CC-BY-NC): https://github.com/DepthAnything/Depth-Anything-V2 ; Depth Anything 3 model licence table: https://github.com/ByteDance-Seed/Depth-Anything-3
- Open3D (MIT): https://github.com/isl-org/Open3D ; meshoptimizer (MIT): https://github.com/zeux/meshoptimizer ; glTF-Transform (MIT): https://github.com/donmccurdy/glTF-Transform ; gltf-pipeline (Apache-2.0): https://github.com/CesiumGS/gltf-pipeline ; xatlas (MIT): https://github.com/jpcy/xatlas ; trimesh (MIT): https://github.com/mikedh/trimesh ; Draco (Apache-2.0): https://github.com/google/draco ; KTX-Software (Apache-2.0): https://github.com/KhronosGroup/KTX-Software ; PyMeshLab (GPL-3.0): https://github.com/cnr-isti-vclab/PyMeshLab
- TRELLIS (MIT): https://github.com/microsoft/TRELLIS ; Hunyuan3D 2.1 licence (territory): https://github.com/Tencent-Hunyuan/Hunyuan3D-2.1/blob/main/LICENSE
- Timing references: COLMAP dense 1,326 s on 100 images (hardware unstated): https://arxiv.org/pdf/2604.10246 ; 2DGS ≈ 10.9 min/scene on DTU: https://arxiv.org/pdf/2501.08174

**Rendering targets**
- Filament supported glTF extensions (README): https://github.com/google/filament
- Scene Viewer model limits (10 MB, 100k tris, 2048² textures, supported extensions): https://developers.google.com/ar/develop/scene-viewer

**Third-party APIs**
- KIRI Engine API pricing / status: https://kiriengine.app/api ; API docs (image upload limits & formats, video limits, 3DGS, webhooks, 3-day asset retention, status codes): https://docs.kiriengine.app/photo-scan/image-upload/ , https://docs.kiriengine.app/photo-scan/video-upload/ , https://docs.kiriengine.app/3dgs-scan/video-upload/ , https://docs.kiriengine.app/webhooks/creating-webhooks/ , https://docs.kiriengine.app/asset-retention/ , https://docs.kiriengine.app/model/retrieve-3d-model-status/ ; processing time FAQ: https://www.kiriengine.app/faq/scan-processing-time ; local processing FAQ: https://www.kiriengine.app/faq/run-algorithm-locally ; privacy policy: https://www.kiriengine.app/privacy-policy ; EULA (KIRI Innovation (Hongkong) Ltd., contributions licence): https://kiriengine.app/user-agreement ; trial-credit mirror: https://us.fitgap.com/products/004910/kiri-engine
- Luma capture status: https://radiancefields.com/luma-ai ; https://radiancefields.com/luma-ai-to-sunset-flythroughs-on-january-1-2026 ; https://radiancefields.com/radiance-field-uploads-suspended-on-luma-ai
- Polycam (no public API found): https://providers.apievangelist.com/providers/polycam/ ; enterprise platform: https://www.businesswire.com/news/home/20250226523971/en
- RealityScan licence & EULA (§1.2(d) SaaS restriction, $1M threshold, $1,250/seat): https://www.realityscan.com/license ; https://www.realityscan.com/eula
- Niantic Scaniverse: https://www.nianticspatial.com/faq/scaniverse-classic ; https://www.nianticspatial.com/pricing ; https://radiancefields.com/scaniverse-cloud-comes-to-android
- Autodesk Reality Capture API: https://aps.autodesk.com/reality-capture-api ; 2026 client library: https://paulgovan.r-universe.dev/AutoDeskR/NEWS
- Agisoft Metashape pricing (reseller): https://drone-parts-center.com/en/buying-guide-agisoft-metashape-licenses/ ; https://reach.dog/shop/cg-river/products/3-floating-license-pack-agisoft-metashape-professional-license
- Meshy API credits: https://docs.meshy.ai/api/pricing ; plans: https://meshy.ai/pricing ; asset ownership: https://help.meshy.ai/en/articles/9992001-can-i-use-my-generated-assets-for-commercial-projects
- Tripo credits (vendor blog, third-party): https://www.tripo3d.ai/blog/explore/3d-conversion-credits-guide ; https://sorceress.games/blog/tripo-ai-image-to-3d-model-in-your-browser ; https://www.wavespeed.ai/models/tripo3d/v2.5/image-to-3d

**Hosting & storage**
- Modal pricing: https://modal.com/pricing ; cold starts (secondary): https://dev.to/jangwook_kim_e31e7291ad98/modal-labs-serverless-gpu-for-vllm-with-no-yaml-le5
- RunPod pricing: https://www.runpod.io/pricing
- Replicate pricing (private-model idle billing): https://replicate.com/pricing
- Lambda pricing: https://lambda.com/pricing
- AWS g6.xlarge ap-south-1 on-demand/spot: https://www.doit.com/compute/spot/ap-south-1/g6.xlarge ; https://www.devzero.io/instances/aws/g6.xlarge
- GCP g2-standard-8 asia-south1: https://calculator.holori.com/gcp/vm/g2-standard-8/asia-south1
- E2E Networks: https://www.e2enetworks.com/pricing.md ; L4/L40S aggregator: https://www.amcompute.com/gpu-cloud-prices/l4 , https://www.amcompute.com/gpu-cloud-prices/l40s
- Cloudflare R2 pricing: https://developers.cloudflare.com/r2/pricing/
- INR/USD ≈ 95 (2026): https://www.techplained.com/self-host-llms-india

**ARCore / Android**
- Shared camera (stream combinations, 640×480 CPU default, 1080p GPU stream): https://developers.google.com/ar/develop/java/camera-sharing
- Camera configs: https://developers.google.com/ar/develop/java/camera-configs
- Depth API (range, motion requirement, featureless surfaces): https://developers.google.com/ar/develop/depth ; Raw depth & confidence: https://developers.google.com/ar/develop/c/depth/raw-depth ; depth image ~160×120: https://developers.google.cn/ar/reference/java/com/google/ar/core/Frame
- ARCore depth TSDF references: https://isprs-archives.copernicus.org/articles/XLIII-B2-2022/201/2022/isprs-archives-XLIII-B2-2022-201-2022.pdf ; https://github.com/TheActualJacob/lantern ; https://github.com/googlesamples/arcore-depth-lab/

**India DPDP**
- DPDP Rules 2025 overview: https://en.wikipedia.org/wiki/Digital_Personal_Data_Protection_Rules,_2025
- Phased dates, Rule 6 safeguards/processors, Rule 15 / s.16 transfers, retention: https://www.legal500.com/intelligence/india/privacy/operationalising-india%27s-data-protection-regime-analysing-the-newly-notified-dpdp-rules-2025 ; https://www.mondaq.com/india/privacy-protection/1708830/dpdp-act-compliance-mandate

**On-device (tier 2) additions**
- ARCore supported devices (Depth API / ToF notes; parsed 2026-11-05: 1,386 of 1,816 models): https://developers.google.com/ar/devices
- ARCore Depth developer guide (depth off by default, `DepthMode`): https://developers.google.com/ar/develop/java/depth/developer-guide
- InfiniTAM licence (non-commercial): https://github.com/victorprad/InfiniTAM/blob/master/LICENSE ; voxblox (BSD-3): https://github.com/ethz-asl/voxblox ; nvblox (Apache-2.0, CUDA): https://github.com/nvidia-isaac/nvblox ; VDBFusion (MIT): https://github.com/PRBonn/vdbfusion ; OpenVDB (Apache-2.0): https://github.com/AcademySoftwareFoundation/openvdb ; ElasticFusion (non-commercial): https://github.com/mp3guy/ElasticFusion/blob/master/LICENSE.txt ; BundleFusion (CC BY-NC-SA): https://github.com/niessner/BundleFusion
- Open3D ScalableTSDFVolume: https://www.open3d.org/docs/release/python_api/open3d.pipelines.integration.ScalableTSDFVolume.html ; ARM support (experimental, Linux/macOS): https://sources.debian.org/src/open3d/0.19.0-5/docs/arm.rst ; ARCore→Open3D forum thread: https://forum.open3d.org/t/open3d-arcore-camera-extrinsics/365
- 3D Live Scanner: https://sketchfab.com/blogs/community/3d-live-scanner-adds-sketchfab-integration ; source (Apache-2.0, third_party mixed): https://github.com/lvonasek/3DLiveScanner ; developer page (500k+ installs): https://lvonasek.github.io
- Wound-scanner Android depth report: https://portfolio-6ku.pages.dev/projects/wound-scanner
- MultiScan Android scanner: https://3dlg-hcvc.github.io/multiscan/read-the-docs/scanner/index.html
- arXiv 2204.01693 (ARCore SLAM points vs LiDAR, MAE 23–30 cm): https://arxiv.org/pdf/2204.01693
- Depth from motion for smartphone AR (Valentin et al., 2018): https://research.google/pubs/depth-from-motion-for-smartphone-ar/ ; informal 0.5–5 m range test: https://www.andreasjakl.com/easily-create-depth-maps-with-smartphone-ar-part-1/
- Android thermal status API: https://developer.android.com/reference/android/os/PowerManager#getCurrentThermalStatus()

*Items marked "estimate" or "(our arithmetic)" are engineering hypotheses (GPU minutes, file sizes, accuracy bands, failure rates). They must be replaced with measurements from the spike (§9). Items marked "not verified" / "not found" could not be confirmed from a primary source on 2026-11-05.*
