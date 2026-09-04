package com.google.mediapipe.examples.handlandmarker

import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.sqrt

object LandmarkNormalizer {

    data class NormalizedData(
        val x: Float,
        val y: Float,
        val z: Float
    )

    fun normalize(result: HandLandmarkerResult): FloatArray? {
        val landmarks = result.landmarks().firstOrNull() ?: return null
        val handedness = result.handedness().firstOrNull()?.firstOrNull()?.categoryName() ?: "Right"

        // 1. Traslación al origen usando la muñeca (landmark 0)
        val wrist = landmarks[0]
        val translated = landmarks.map {
            Triple(it.x() - wrist.x(), it.y() - wrist.y(), it.z() - wrist.z())
        }

        // 2. Calcular la escala como la norma euclidiana MÁXIMA entre todos los puntos
        var maxNorm = 0.0f
        translated.forEach { (tx, ty, tz) ->
            val norm = sqrt(tx * tx + ty * ty + tz * tz)
            if (norm > maxNorm) maxNorm = norm
        }

        // 3. Dividir todos los puntos por esa escala
        // 4. Si es mano izquierda, espejar en X (* -1)
        val resultVector = FloatArray(63)
        translated.forEachIndexed { i, (tx, ty, tz) ->
            val scaleFactor = if (maxNorm > 0) maxNorm else 1.0f
            
            // Si es Left, invertimos X
            val finalX = if (handedness == "Left") -(tx / scaleFactor) else (tx / scaleFactor)
            val finalY = ty / scaleFactor
            val finalZ = tz / scaleFactor

            resultVector[i * 3] = finalX
            resultVector[i * 3 + 1] = finalY
            resultVector[i * 3 + 2] = finalZ
        }

        return resultVector
    }
}
