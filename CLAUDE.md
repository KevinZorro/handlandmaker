# SignaCO hand landmarker (Android)

Kotlin + Jetpack Compose app: CameraX frames → MediaPipe Hand Landmarker → 63 normalized floats →
TFLite sign classifier (LSC alphabet and digits). Also records landmark datasets to CSV.

## Commands
- Unit tests (JVM): `./gradlew :app:testDebugUnitTest`
- Build/install: `./gradlew :app:installDebug` (physical device; needs the camera)
- Classifier debug log: `adb logcat -s SignaCO_Debug`

## Layout (`app/src/main/java/com/google/mediapipe/examples/handlandmarker/`)
- `ModelSpec.kt`: parses and validates `config_modelo_final.json`; single source of labels.
- `SignaClassifier.kt`: TFLite inference, rejection, `k`-frame smoothing.
- `LandmarkNormalizer.kt`: wrist origin, max-norm scale, mirror X for the left hand.
- `MainScreen.kt`: setup, recording and validation UI. `SessionLogger.kt`: CSV export.

## Gotchas
- Model and config are a pair: see `docs/model.md` before swapping either.
- TFLite op versions must be supported by `org.tensorflow:tensorflow-lite` in `app/build.gradle`.
  A newer converter op version makes the interpreter fail to load (`FULLY_CONNECTED` v12 needed ≥ 2.17).
- `.tflite` and `hand_landmarker.task` assets are committed on purpose: the APK needs them.
- Images/videos of people are personal data (Ley 1581): never commit raw recordings.
