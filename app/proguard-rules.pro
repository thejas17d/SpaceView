# ARCore, Filament/SceneView, Compose, Room and DataStore ship their own consumer keep rules.
# Add narrow, app-specific rules below only after a release build proves they are needed.
# Never add blanket package keeps such as -keep class com.google.android.filament.** { *; }:
# they disable most R8 optimisation.
