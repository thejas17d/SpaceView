# ARCore, Filament/SceneView, Compose, Room and DataStore ship their own consumer keep rules.
# Add narrow, app-specific rules below only after a release build proves they are needed.
# Never add blanket package keeps such as -keep class com.google.android.filament.** { *; }:
# they disable most R8 optimisation.

# M6 TODO (M2 review minor 15): SceneView 4.65.0's consumer rules (arsceneview/sceneview
# proguard.txt) are blanket keeps that defeat the rule above and cost dex size:
#   -keep class com.google.android.filament.** { *; }
#   -keep class com.google.ar.core.** { *; }
#   -keep class dev.romainguy.kotlin.math.** { *; }
#   -keep class io.github.sceneview.collision.** { *; }
#   -keep class com.google.android.gms.location.** / gms.common.api.** / gms.tasks.** { *; }
# Not narrowed yet: there is no crash risk with them, but a narrowed set can only fail at run
# time (JNI FindClass/RegisterNatives in Filament, reflection in SceneView), so it must be proven
# on a device. Plan for M6:
#   1. drop the library's rules with AGP's
#      optimization { keepRules { ignoreFrom("io.github.sceneview:arsceneview", "io.github.sceneview:sceneview") } }
#      (Filament's and ARCore's own consumer rules, which keep only their @UsedBy* / native
#      members, still apply);
#   2. re-add only what is needed (SceneView's Node#isFrameActive / #onFrame name keeps; the
#      collision package only if the release build or a device run shows it is reflected on;
#      no gms.location keeps, since Geospatial mode is never enabled);
#   3. compare the R8 size, then run the device checklist (measure, calibrate, freeze-frame,
#      exit with points placed) on a release build before shipping.
