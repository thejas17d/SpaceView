# Phase 0 contract (frozen): AR frame-listener seam and GLB writer

Builder A, 2026-11-06. This is the API that Builder B (templates) and Builder C (recon) code against. It implements
`docs/ARCHITECTURE-v2.md` S1, K8, K9, K11, ADR-001, ADR-003 and ADR-014. **Frozen:** signatures below change only by
a new contract revision agreed with the orchestrator. Adding an optional parameter with a default is allowed.

## 1. GLB writer (`:engine`, package `com.spaceview.engine`)

Files: `engine/src/main/kotlin/com/spaceview/engine/GlbWriter.kt`, `GlbModel.kt`, `GlbReader.kt`.

```kotlin
const val GLB_VERSION = 2                                   // extras.glbVersion of every model written now

enum class ModelKind { PROXY_BOX, TEMPLATE, MESH_DEVICE, MESH_CLOUD; val regenerable: Boolean }
enum class BoxOrigin { CENTER, BOTTOM_CENTER, BACK_CENTER } // unchanged
enum class PartMaterial { BODY, SOFT, FRAME, LEG, DOOR, ACCENT }

data class Part(
    val name: String,
    val center: Vec3,               // metres, model frame: +Y up, +Z = front (towards the viewer), +X = viewer's right
    val size: Vec3,                 // full extents along the part's own X, Y, Z; all > 0
    val yawRad: Double = 0.0,       // about +Y, right-handed (positive turns +X towards −Z), about `center`
    val material: PartMaterial = PartMaterial.BODY,
)
class Palette(colors: Map<PartMaterial, Int> = emptyMap()) {  // sRGB 0xRRGGBB; missing entries → DEFAULT
    fun colorOf(m: PartMaterial): Int
    companion object { val DEFAULT: Palette }
}
class MeshExtras(
    val modelKind: ModelKind = ModelKind.MESH_DEVICE,
    val name: String = "SpaceView 3D scan",
    val values: Map<String, Double> = emptyMap(),           // extra numeric extras; keys [A-Za-z][A-Za-z0-9_]*, not reserved
)

object GlbWriter {
    const val MAX_SIDE_M = 50.0
    const val MAX_PARTS = 512
    const val MAX_MESH_VERTICES = 4_000_000

    fun writeBox(widthM: Double, heightM: Double, depthM: Double, frontJpeg: ByteArray? = null,
                 sideColorSrgb: Int = 0x808080, frontColorSrgb: Int = 0xFFFFFF,
                 origin: BoxOrigin = BoxOrigin.BOTTOM_CENTER, name: String = "SpaceView item",
                 modelKind: ModelKind = ModelKind.PROXY_BOX): ByteArray

    fun writeParts(parts: List<Part>, palette: Palette = Palette.DEFAULT,
                   origin: BoxOrigin = BoxOrigin.BOTTOM_CENTER, name: String = "SpaceView furniture",
                   modelKind: ModelKind = ModelKind.TEMPLATE): ByteArray

    fun writeMesh(mesh: TriMesh, origin: BoxOrigin = BoxOrigin.BOTTOM_CENTER, unlit: Boolean = true,
                  extras: MeshExtras = MeshExtras(), keepInputOrigin: Boolean = false): ByteArray
}

enum class BoxOrigin { …; fun centreOffset(w: Double, h: Double, d: Double): Vec3 }   // the one origin → centre rule

class GlbInfo(val widthM: Double, val heightM: Double, val depthM: Double,
              val origin: BoxOrigin?, val glbVersion: Int, val modelKind: ModelKind?)
object GlbReader {
    fun readInfo(input: InputStream): GlbInfo?   // reads the 20-byte header + JSON chunk only; never throws
    fun readInfo(glb: ByteArray): GlbInfo?
}
```

### Rules every writer follows (K8)

- **Units and axes:** 1 unit = 1 m, +Y up, front = +Z. Output is deterministic (same input → same bytes).
- **Origin:** the geometry is **translated** (never scaled or rotated) so that the `origin` point of its own
  axis-aligned bounding box is the model origin. Exception (M4 review MAJOR 2): `writeMesh(keepInputOrigin = true)`
  writes the positions unchanged, because a 3D scan is fused in the measured box's frame, whose origin is already the
  centre of the box base on the support plane. The scan then stands where it was measured: its bottom is the floor
  cut (about 1 cm) above the origin, and a partial scan is not re-centred on its own box. The D milestone must use
  this. The frame origin must lie within 0.5 m of the mesh's box on every axis. `BOTTOM_CENTER` = centre of the bottom face (floor, table, templates,
  meshes). `BACK_CENTER` = centre of the back face (wall items: frames, TVs).
- **Node-0 extras:** `widthM`, `heightM`, `depthM` = the GLB's own bounding box (for parts: the union of all parts,
  which templates guarantee equals W×H×D; for meshes: the extents of every vertex), plus `origin`,
  `glbVersion` (= `GLB_VERSION`) and `modelKind`. `writeMesh` adds `MeshExtras.values` after these.
  A `keepInputOrigin` mesh also declares its own bounding-box centre in the GLB frame as `aabbCx`, `aabbCy`, `aabbCz`
  (reserved keys; M4-P1 gate N3), read back as `GlbInfo.centre` → `PlacementModel.centre` → `PlacedItem`. Placement
  uses it for wall targets and the wall centre-height readout; floor and table placement keep the declared origin
  (the measured base). Absent on every other model, whose centre is `origin.centreOffset(w, h, d)`.
- **Validation:** `IllegalArgumentException` on bad input (non-finite numbers, sides ≤ 0, a bounding-box side over
  50 m, malformed mesh arrays, an index out of range, bad extras keys).

### Per writer

| | `writeBox` | `writeParts` | `writeMesh` |
|---|---|---|---|
| Geometry | 24 vertices, POSITION/NORMAL/TEXCOORD_0 | 24 vertices per part, POSITION/NORMAL | the mesh; NORMAL = area-weighted vertex normals computed by the writer (degenerate-only vertices get +Y) |
| Primitives | front (+Z face), sides | **one per material used**, in `PartMaterial` order | one |
| Materials | front: photo texture and **`KHR_materials_unlit`** when a JPEG is given (ADR-003); otherwise lit flat colour. Sides lit | lit PBR, `baseColorFactor` from the palette (sRGB → linear), metallic 0, roughness 0.85 | `doubleSided: true`; white × `COLOR_0` with colours, neutral grey without; `KHR_materials_unlit` iff `unlit` |
| Indices | u16 | u16 | u16 when ≤ 65,535 vertices, else u32 |
| Colours | — | — | `COLOR_0` RGBA8 normalised; input RGB gets alpha 255 |
| Default `modelKind` | `PROXY_BOX` | `TEMPLATE` | `MESH_DEVICE` (via `MeshExtras`) |

### Size

- Box: ~2–3 KB + the JPEG.
- Parts: about 1 KB per part (a 9-part sofa is under 12 KB).
- Mesh: about 20 B per triangle (positions, normals, colours, u16 indices). A 15k-triangle coloured mesh is about
  310 KB, so it fits the 0.5 MB per-item budget (`GalleryRepository.MAX_SCAN_BYTES`) with the thumbnail.

## 2. `TriMesh` (K11)

Location: **`engine/src/main/kotlin/com/spaceview/engine/recon/TriMesh.kt`**, package `com.spaceview.engine.recon`
(Builder C's package). Builder A created it with exactly the §7.1.4 definition; Builder C owns it from now on and may
add members, but not change the constructor:

```kotlin
class TriMesh(val positions: FloatArray, val colours: ByteArray, val indices: IntArray)
// positions: x, y, z per vertex (m). colours: empty, RGB (3/vertex) or RGBA (4/vertex). indices: CCW from outside.
```

## 3. AR frame-listener seam (S1, ADR-001)

Files: `ar/src/main/kotlin/com/spaceview/ar/ArFrameListener.kt`, `app/src/main/kotlin/com/spaceview/app/common/ar/ArSessionHost.kt`.

```kotlin
// :ar
data class ArRequirements(val depth: Boolean = false, val lights: Boolean = false, val planeRenderer: Boolean = true) {
    infix fun and(o: ArRequirements): ArRequirements
}
interface ArFrameListener {
    val requirements: ArRequirements                        // default ArRequirements()
    fun selectCameraConfig(session: Session): CameraConfig  // primary only; default SessionSetup.selectCameraConfig
    fun configureSession(session: Session, config: Config)  // primary only; default SessionSetup.configure(depth off)
    fun onSessionCreated(session: Session) {}
    fun onSessionResumed(session: Session) {}
    fun onSessionPaused(session: Session) {}               // last moment anchors may be detached
    fun onFrame(session: Session, frame: Frame)
}
class ArFrameDispatcher(val listeners: List<ArFrameListener>) {   // non-empty
    val primary: ArFrameListener                            // listeners[0]
    val requirements: ArRequirements                        // merged
    // selectCameraConfig/configureSession → primary; the session callbacks and onFrame → every listener, in order
}

// :app
@Composable fun ArSessionHost(listeners: List<ArFrameListener>, planeRenderer: Boolean, onSessionFailed: () -> Unit,
                              modifier: Modifier = Modifier /* fillMaxSize is applied */,
                              content: (@Composable ARSceneScope.() -> Unit)? = null)
```

- All callbacks run on the **main thread, in list order**. `MeasureSession` implements `ArFrameListener` and is the
  primary listener wherever it is present. Its logic did not change.
- `MeasureSession.latestCentreHit` (internal to `:ar`) is the reticle hit of the frame being dispatched, so a listener
  placed after it needs no second `frame.hitTest` (§3.5).
- Lights: the host keeps SceneView's default main/fill light and AR environment only when some listener sets
  `requirements.lights` (Place). Measure and Scan keep none, as before.
- `app/measure/MeasureScreen.kt` `ArCameraView` now delegates to `ArSessionHost`; it gained optional
  `extraListeners` and `content` parameters.

## 4. Other phase 0 changes

- **R1 / D6 (user-approved):** `FitPolicy(combination: UncertaintyCombination = LINEAR)`, with
  `UncertaintyCombination { LINEAR, RSS }`. LINEAR (the **default**): σc = σs + σi, so the decision band is the sum of
  the two 95% bounds. RSS (σc = √(σs² + σi²)) is an explicit opt-in only; no user-facing Fits / Tight / Too big
  verdict uses it. Every `FitVerdict.evaluate` caller therefore gets the linear sum unless it asks otherwise.
- **R3:** `MeasureSession` per-frame allocations removed: pooled `FrameSample`s (8, array-backed queue), each with its
  own point-cloud buffer and endpoint list, filled in place; the worker returns them in a `finally`, and the channel
  returns dropped ones through `onUndeliveredElement`. `FrameReaders.pointCloud` writes the timestamp into a
  caller-owned `LongArray` instead of returning a `Pair`. `HitSample` (only when there is a hit), the camera pose and
  `ViewProjection` are still allocated per frame (small; next candidates if the device profile asks).

## 5. Addendum (M4): persistence contracts K4 and K10, Room v3

Files: `data/src/main/kotlin/com/spaceview/data/gallery/GalleryDrafts.kt`, `GalleryRepository.kt`,
`SpaceViewDatabase.kt`.

```kotlin
// :data, package com.spaceview.data.gallery
enum class ObjectKind { FRAME, TV, APPLIANCE, BOX, OTHER, FURNITURE }        // FURNITURE added (v3)
enum class CaptureMethod { MANUAL, HANDS_FREE, AUTO_RECT, DEPTH_SCAN }

class TemplateDraft(templateId: String, params: Map<String, Double>, width: Dimension, height: Dimension,
                    depth: Dimension, label: String?, parts: List<Part>, palette: Palette = Palette.DEFAULT,
                    deviceTier: DeviceTier = NO_DEPTH, calibrated: Boolean = false, calibrationFactor: Double? = null,
                    arcoreVersion: String? = null, captureMethod: CaptureMethod? = null)
class MeshDraft(kind, width, height, depth: Dimension /* the MEASURED box */, quality, calibrated, deviceTier,
                glb: ByteArray /* GlbWriter.writeMesh(…, keepInputOrigin = true), BOTTOM_CENTER */,
                glbExtents: GlbExtents /* GlbReader.readInfo(glb): the GLB's own box */,
                meshExtents: MeshExtents /* §7.1.5 MeshExtent.estimate, with ± */, meshInfo: String?,
                thumbnailSource: ByteArray? /* JPEG */, label: String?, arcoreVersion: String?,
                calibrationFactor: Double? = null, modelKind: ModelKind = MESH_DEVICE,
                captureMethod: CaptureMethod? = DEPTH_SCAN)
data class MeshExtents(widthM, heightM, depthM, widthBound95, heightBound95, depthBound95: Double)
data class GlbExtents(widthM, heightM, depthM: Double)
sealed interface PlacementResult { Ready(model: PlacementModel); Missing; Failed }
fun interface TemplateModelSource { fun parts(templateId: String, params: Map<String, Double>): List<Part>? }

suspend fun GalleryRepository.saveTemplate(draft: TemplateDraft): Long   // K10
suspend fun GalleryRepository.saveMesh(draft: MeshDraft): Long           // K10
suspend fun GalleryRepository.modelForPlacement(id: Long): PlacementModel?   // K4 (null = missing or failed)
suspend fun GalleryRepository.placementModel(id: Long): PlacementResult      // K4, tells Missing from Failed
class PlacementModel(id, file: File, widthM, heightM, depthM /* the rendered GLB */, origin: BoxOrigin,
                     kind: ObjectKind, width, height, depth: Dimension /* the row: used by the verdict */,
                     modelKind: ModelKind, meshFallback: Boolean, label: String?)
GalleryRepository.create(context, templates: TemplateModelSource = TemplateModelSource.NONE)
```

- **saveTemplate:** writes `GlbWriter.writeParts(parts, palette, BOTTOM_CENTER, label, TEMPLATE)`; refuses
  (`IllegalArgumentException`) parts whose union box is not W×H×D to 0.1 mm, bad parameter keys
  (`[A-Za-z][A-Za-z0-9_]*`) or non-finite values. Row: `kind = FURNITURE`, `model_kind = TEMPLATE`, `template_id`,
  `template_params` (JSON object of numbers). No photo or thumbnail. Same staging/commit/budget path as `save`.
- **saveMesh:** the GLB must read back (GlbReader) as the draft's mesh kind with origin BOTTOM_CENTER and extras equal
  to `glbExtents` to 0.1 mm, and be ≤ `MAX_SCAN_BYTES`. W/H/D are the measured box. The §7.1.5 extents with their ±
  go to `mesh_extent_*`; the GLB's own box goes to `mesh_glb_*` (M4 review MAJOR 2: the two differ by the floor cut and
  the percentile trims, so both are kept). Placement checks the GLB against `mesh_glb_*`. Callers first check
  `container.gallerySaveGate.checkDeviceScan()` (Builder B's gate; `checkItem()` for `saveTemplate`). A 256 px WebP thumbnail is made from `thumbnailSource`; no photo is stored.
- **Edits:** `updateDimensions` on a TEMPLATE returns false (resize through the furniture flow); on a mesh it changes
  the row only and never touches the mesh file.
- **Template regeneration** (stale or missing template model) asks the injected `TemplateModelSource`
  (Builder B's registry, wired by the orchestrator in `AppContainer`). Without it, placement shows the measured box
  from the cache and the row is untouched.
- **Room v3** (`MIGRATION_2_3`, additive only): `gallery_items` + `model_kind TEXT NOT NULL DEFAULT 'PROXY_BOX'`,
  `template_id`, `template_params`, `mesh_extent_{w,h,d}_m`, `mesh_extent_{w,h,d}_bound95_m`, `mesh_glb_{w,h,d}_m`
  (amended in place for MAJOR 2), `mesh_info`,
  `capture_method`; `measurements` + `capture_method`. Amendable in place only until an APK with v3 leaves the team.
