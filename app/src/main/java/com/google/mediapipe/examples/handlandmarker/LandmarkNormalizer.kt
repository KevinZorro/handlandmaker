package com.google.mediapipe.examples.handlandmarker

import android.util.Log
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.abs
import kotlin.math.sqrt

object LandmarkNormalizer {

    private const val TAG = "SignaCO_Debug"

    /** Poner en false para silenciar el volcado por frame en Logcat. */
    var VERBOSE = true

    data class NormalizedData(
        val x: Float,
        val y: Float,
        val z: Float
    )

    /** Datos de la última normalización, para que el clasificador los incluya en su volcado. */
    @Volatile
    var lastHandedness: String = "?"
        private set

    @Volatile
    var lastHandednessScore: Float = -1f
        private set

    @Volatile
    var lastScale: Float = -1f
        private set

    @Volatile
    var lastMirrored: Boolean = false
        private set

    fun normalize(result: HandLandmarkerResult): FloatArray? {
        val landmarks = result.landmarks().firstOrNull() ?: return null
        val handCategory = result.handedness().firstOrNull()?.firstOrNull()
        val handedness = handCategory?.categoryName() ?: "Right"

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
        val mirrored = handedness == "Left"
        val scaleFactor = if (maxNorm > 0) maxNorm else 1.0f
        val resultVector = FloatArray(63)
        translated.forEachIndexed { i, (tx, ty, tz) ->
            val finalX = if (mirrored) -(tx / scaleFactor) else (tx / scaleFactor)
            val finalY = ty / scaleFactor
            val finalZ = tz / scaleFactor

            resultVector[i * 3] = finalX
            resultVector[i * 3 + 1] = finalY
            resultVector[i * 3 + 2] = finalZ
        }

        lastHandedness = handedness
        lastHandednessScore = handCategory?.score() ?: -1f
        lastScale = maxNorm
        lastMirrored = mirrored

        if (VERBOSE) {
            Log.d(TAG, "  [NORM] handedness=$handedness (score=%.3f) espejadoX=%b escala(maxNorm)=%.6f"
                .format(lastHandednessScore, mirrored, maxNorm))
            Log.d(TAG, "  [NORM] muñeca cruda=(%.5f, %.5f, %.5f)  (MediaPipe: x,y en [0,1], y crece hacia ABAJO)"
                .format(wrist.x(), wrist.y(), wrist.z()))
            Log.d(TAG, "  [NORM] " + axisStats(resultVector))
        }

        return resultVector
    }

    /**
     * Estadísticos por eje del vector normalizado. Sirven para detectar de un vistazo
     * una convención invertida: con la mano en vertical y dedos hacia arriba, los "y"
     * normalizados deben ser mayoritariamente NEGATIVOS (y de imagen crece hacia abajo).
     */
    fun axisStats(v: FloatArray): String {
        val sb = StringBuilder()
        val names = arrayOf("x", "y", "z")
        for (axis in 0..2) {
            var mn = Float.MAX_VALUE
            var mx = -Float.MAX_VALUE
            var sum = 0f
            var negativos = 0
            for (i in 0 until 21) {
                val value = v[i * 3 + axis]
                if (value < mn) mn = value
                if (value > mx) mx = value
                sum += value
                if (value < 0f) negativos++
            }
            sb.append("%s[min=%.3f max=%.3f media=%.3f neg=%d/21] "
                .format(names[axis], mn, mx, sum / 21f, negativos))
        }
        var maxAbs = 0f
        v.forEach { if (abs(it) > maxAbs) maxAbs = abs(it) }
        sb.append("maxAbs=%.3f".format(maxAbs))
        return sb.toString()
    }
}
