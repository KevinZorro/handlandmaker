# Plan de Implementación: Herramienta de Captura SignaCO

Este plan detalla la evolución del spike a una herramienta interna para la grabación de datasets de señas.

## User Review Required

> [!IMPORTANT]
> Se ha definido una lista inicial de clases (señas) en `MainScreen.kt`. El equipo puede editar esta lista directamente en el código según sea necesario.

## Proposed Changes

### Core Logic & Data Handling

#### [NEW] [LandmarkNormalizer.kt](file:///C:/Users/kevin/Desktop/Enginer/mediapipe-samples-main/mediapipe-samples-main/examples/hand_landmarker/android/app/src/main/java/com/google/mediapipe/examples/handlandmarker/LandmarkNormalizer.kt)
- Implementación de la lógica de traslación al origen (muñeca).
- Escalado basado en la distancia muñeca-base dedo medio.
- Espejado (mirroring) para manos izquierdas.

#### [NEW] [SessionLogger.kt](file:///C:/Users/kevin/Desktop/Enginer/mediapipe-samples-main/mediapipe-samples-main/examples/hand_landmarker/android/app/src/main/java/com/google/mediapipe/examples/handlandmarker/SessionLogger.kt)
- Gestión de archivos CSV en `getExternalFilesDir`.
- Escritura de filas con datos crudos y normalizados.
- Funcionalidad de descarte de tomas.
- Integración con el Share Sheet para exportación.

### UI & UX (Compose)

#### [MODIFY] [MainScreen.kt](file:///C:/Users/kevin/Desktop/Enginer/mediapipe-samples-main/mediapipe-samples-main/examples/hand_landmarker/android/app/src/main/java/com/google/mediapipe/examples/handlandmarker/MainScreen.kt)
- **Modo Setup**: Formulario para Participant ID, Condición y Clase.
- **Modo Captura**:
    - Preview con landmarks.
    - Botón de grabación tipo "hold-to-record" o "toggle".
    - Contadores de muestras y frames descartados.
    - Indicadores visuales de detección.
    - Navegación entre clases.

### Configuration

#### [MODIFY] [AndroidManifest.xml](file:///C:/Users/kevin/Desktop/Enginer/mediapipe-samples-main/mediapipe-samples-main/examples/hand_landmarker/android/app/src/main/AndroidManifest.xml)
- Configurar `FileProvider` para permitir compartir el CSV.

#### [NEW] [filepaths.xml](file:///C:/Users/kevin/Desktop/Enginer/mediapipe-samples-main/mediapipe-samples-main/examples/hand_landmarker/android/app/src/main/res/xml/filepaths.xml)
- Definir rutas para el `FileProvider`.

## Verification Plan

### Manual Verification
1. Iniciar sesión con un ID y condición.
2. Grabar una seña y verificar que el contador de muestras aumente.
3. Ocultar la mano y verificar que el indicador cambie a "No detectado" y no se graben frames.
4. Descartar una toma y verificar que el contador retroceda.
5. Exportar el CSV y abrirlo en una PC para validar:
    - Traslación al origen (muñeca en 0,0,0 en columnas normalizadas).
    - Espejado correcto si se usó la mano izquierda.
    - Presencia de columnas crudas y normalizadas.
