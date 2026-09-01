package com.google.mediapipe.examples.handlandmarker

import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.sqrt

object LandmarkNormalizer {

    data class NormalizedData(
        val x: Float,
        val y: Float,
        val z: Float
    )

    fun normalize(result: HandLandmarkerResult): List<NormalizedData>? {
        val landmarks = result.landmarks().firstOrNull() ?: return null
        val handedness = result.handedness().firstOrNull()?.firstOrNull()?.categoryName() ?: "Right"

        // 1. Traslación al origen usando la muñeca (landmark 0)
        val wrist = landmarks[0]
        val translated = landmarks.map {
            Triple(it.x() - wrist.x(), it.y() - wrist.y(), it.z() - wrist.z())
        }

        // 2. Escalado (distancia muñeca 0 a base dedo medio 9)
        val mcpMiddle = translated[9]
        val distance = sqrt(
            mcpMiddle.first * mcpMiddle.first +
            mcpMiddle.second * mcpMiddle.second +
            mcpMiddle.third * mcpMiddle.third
        )

        val scaled = if (distance > 0) {
            translated.map {
                Triple(it.first / distance, it.second / distance, it.third / distance)
            }
        } else {
            translated
        }

        // 3. Espejado si es mano izquierda (eje X)
        // MediaPipe reporta handedness relativa a la imagen. 
        // Si queremos unificar todo a "Derecha", invertimos X si es "Left".
        return scaled.map {
            val finalX = if (handedness == "Left") -it.first else it.first
            NormalizedData(finalX, it.second, it.third)
        }
    }
}
