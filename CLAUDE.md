# SignaCO hand landmarker (Android)

Kotlin + Jetpack Compose app: CameraX frames → MediaPipe Hand Landmarker → 63 normalized floats →
TFLite sign classifier (LSC alphabet and digits). Single-screen kiosk demo for a university fair:
opens straight into the camera, no setup or recording (the lab recording mode was removed; it is in git history).

## Commands
- Unit tests (JVM): `./gradlew :app:testDebugUnitTest`
- Build/install: `./gradlew :app:installDebug` (physical device; needs the camera)
- Classifier debug log: `adb logcat -s SignaCO_Debug` (off by default; enable the `VERBOSE` flags in `MainActivity`)

## Layout (`app/src/main/java/com/google/mediapipe/examples/handlandmarker/`)
- `ModelSpec.kt`: parses and validates `config_modelo_final.json`; single source of labels.
- `SignaClassifier.kt`: TFLite inference, rejection, `k`-frame smoothing.
- `LandmarkNormalizer.kt`: wrist origin, max-norm scale, mirror X for the left hand.
- `MainScreen.kt`: permission, camera and kiosk wiring. `MainActivity.kt`: keep-screen-on, hidden system bars.
- `RecognitionUi.kt`: pure state for what the kiosk shows (sign hold, linger, `NN` shown as `Ñ`).
- `HandOverlay.kt` / `FillCenterMapping.kt`: skeleton and focus corners mapped through the preview's FILL_CENTER crop.
- `ResultCard.kt`: bottom glass card (sign, status, confidence bar) and the kiosk palette.

## Gotchas
- Model and config are a pair: see `docs/model.md` before swapping either.
- TFLite op versions must be supported by `org.tensorflow:tensorflow-lite` in `app/build.gradle`.
  A newer converter op version makes the interpreter fail to load (`FULLY_CONNECTED` v12 needed ≥ 2.17).
- `.tflite` and `hand_landmarker.task` assets are committed on purpose: the APK needs them.
- Images/videos of people are personal data (Ley 1581): never commit raw recordings.
