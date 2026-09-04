# Plan de Integración: Clasificador SignaCO (TFLite)

Este plan detalla la integración del modelo clasificador de abecedario LSC sobre el sistema de detección de landmarks de MediaPipe ya existente.

## Análisis del Config (config_rechazo.json)

El archivo está estructurado de la siguiente manera:
- `etiquetas`: Lista de 27 letras (A a Z, incluyendo NN).
- `indice_rechazo`: 27 (La clase 28 del modelo es "no_es_seña").
- `umbral_por_letra`: Diccionario con umbrales específicos para cada letra.
- `umbral_global_fallback`: 0.1.
- **Nota**: El parámetro `k` no está en el JSON leído; se utilizará un valor por defecto de 3 (configurable en código) conforme al feedback del usuario.

## Proposed Changes

### Core Logic

#### [MODIFY] [LandmarkNormalizer.kt](file:///C:/Users/kevin/Desktop/Enginer/mediapipe-samples-main/mediapipe-samples-main/examples/hand_landmarker/android/app/src/main/java/com/google/mediapipe/examples/handlandmarker/LandmarkNormalizer.kt)
- **Cambio Crítico**: Actualizar la escala para usar la **norma euclidiana máxima** de todos los puntos trasladados en lugar de la distancia muñeca-punto 9.
- Mantener la traslación al origen y el espejado de mano izquierda.

#### [NEW] [SignaClassifier.kt](file:///C:/Users/kevin/Desktop/Enginer/mediapipe-samples-main/mediapipe-samples-main/examples/hand_landmarker/android/app/src/main/java/com/google/mediapipe/examples/handlandmarker/SignaClassifier.kt)
- Carga del modelo `signaco_abecedario_rechazo.tflite` y del JSON.
- Implementación de la inferencia con `Interpreter`.
- Lógica de rechazo por umbral específico.
- **Estabilidad Temporal**: Buffer de $k$ frames para evitar parpadeos.

### UI & UX (Compose)

#### [MODIFY] [MainScreen.kt](file:///C:/Users/kevin/Desktop/Enginer/mediapipe-samples-main/mediapipe-samples-main/examples/hand_landmarker/android/app/src/main/java/com/google/mediapipe/examples/handlandmarker/MainScreen.kt)
- Integrar el flujo de inferencia en el loop de procesamiento de frames.
- **Overlay**: Mostrar letra predicha, confianza, tiempo de inferencia de TFLite y FPS.
- **Modo Test Manual**: Botón para seleccionar letra objetivo y llevar contador de aciertos/fallos.
- **Logcat**: Botón para imprimir el vector de 63 floats.

### Dependencies

#### [MODIFY] [build.gradle (app)](file:///C:/Users/kevin/Desktop/Enginer/mediapipe-samples-main/mediapipe-samples-main/examples/hand_landmarker/android/app/build.gradle)
- Agregar `org.tensorflow:tensorflow-lite:2.14.0` y soporte para Gson para el JSON.

## Verification Plan

### Manual Verification
1. Verificar en Logcat que el modelo e intérprete carguen sin errores.
2. Realizar señas conocidas (A, B, C) y verificar que el overlay muestre la letra correcta.
3. Verificar que al ocultar la mano o hacer una seña no válida se muestre "seña no reconocida".
4. Probar la estabilidad temporal: la letra no debe cambiar si se mueve ligeramente la mano por menos de $k$ frames.
5. Usar el modo test manual para validar una letra específica y ver el contador de aciertos.
6. Presionar el botón de log y comparar el vector de 63 floats con los valores esperados.
