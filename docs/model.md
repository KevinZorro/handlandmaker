# Sign classifier: model card

Current model: **modelo_final_v2** (Drive folder `modelo_final_v2`).

| | |
|---|---|
| Asset | `app/src/main/assets/signaco_modelo_final.tflite` (218 164 bytes, sha256 `b49c509b6c79427f…`) |
| Config | `app/src/main/assets/config_modelo_final.json` |
| Type | Dense MLP, float32, **not quantized**. Ops: `FULLY_CONNECTED` v1, `SOFTMAX` v1 |
| Converted with | TensorFlow 2.20.0, seed 42. Loads on the app runtime `tensorflow-lite:2.17.0` |
| Input | `[1, 63]` float32: 21 MediaPipe hand landmarks × (x, y, z), normalized by `LandmarkNormalizer` |
| Output | `[1, 36]` softmax: 35 signs + `no_es_seña` (rejection, last index) |
| Classes | `A`–`Z` with `NN` after `N`, then digits `1 4 5 6 7 8 9 10`. No `0`, `2` or `3`: in LSC they share the handshape of `O`, `V` and `W` |

## Model/config contract

The `.tflite` has no label metadata: **the output order exists only in the config**. A wrong
order raises no error at inference, it silently maps scores to the wrong signs. `ModelSpec`
enforces everything checkable without labelled samples, and any failure blocks the app with an
on-screen error instead of degrading to "seña no reconocida":

- `modelo` names the asset to load, so a config cannot be paired with another model by filename.
- `etiquetas` has no duplicates; `indice_rechazo` is the last index and labels `no_es_seña`.
- The model output size equals `len(etiquetas)` and the input is `[1, 63]`.
- `umbral_por_letra` (optional) only names signs the model outputs.

The kiosk shows the labels from `etiquetas` as-is, except `NN`, which it displays as `Ñ`
(`RecognitionUi.kt`). For `O`, `V` and `W` the caption also names the digit with the same handshape
(`LETRA · NÚMERO 0` / `2` / `3`). A permutation of labels with the same count **cannot** be detected this way; `ModelSpecTest.shippedConfigMatchesTheBundledModelContract` pins the
expected order of the shipped config.

## Decision rule

Plain argmax over 36 classes (the config ships no calibrated thresholds). A sign is shown after
`k = 3` identical consecutive frames (`MainScreen`).

Rejection check from cross-validation (2 438 takes), as reported by the config:

| Threshold | False rejection | False acceptance |
|---|---|---|
| 0.0 (current) | 1.6 % | 5.8 % |
| 0.5 | 14.6 % | 3.6 % |
| 0.7 | 36.4 % | 1.2 % |
| 0.9 | 58.7 % | 0.3 % |

## Known limitations

- Trained on **all participants**: there is no held-out set. Confirm the CV folds were grouped by
  participant; frame- or take-level folds overestimate accuracy for unseen signers.
- Not yet verified with the camera (`verificado_con_camara: false`).
- The config's warning mentions "argmax de 38 clases" but the model outputs 36; worth confirming
  how negative classes were merged at export.
- Offline sanity check: three device-captured, normalized "A" frames score `A` ≥ 0.985.

## Replacing the model

Copy the new `.tflite` and its config into `app/src/main/assets/`, set `modelo` to the new file
name, update the expected label order in `ModelSpecTest`, and run `./gradlew :app:testDebugUnitTest`.
Check the op versions against the TFLite runtime in `app/build.gradle` before shipping.
