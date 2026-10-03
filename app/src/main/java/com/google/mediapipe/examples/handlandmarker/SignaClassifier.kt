package com.google.mediapipe.examples.handlandmarker

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs

/**
 * Runs the sign model on normalized landmarks. Construction throws [ModelSpecException] when the
 * model cannot be loaded or does not match [spec]: a classifier that cannot classify must never
 * report its silence as "seña no reconocida".
 */
class SignaClassifier(context: Context, private val spec: ModelSpec, private val k: Int = 3) {

    private val interpreter: Interpreter = loadInterpreter(context)
    private var frameCounter: Long = 0

    // Buffer para suavizado temporal (RF07)
    private val predictionBuffer = mutableListOf<String>()

    data class PredictionResult(
        val label: String,
        val confidence: Float,
        val isRecognized: Boolean,
        val inferenceTime: Long,
        /** This frame's argmax is a sign rather than the rejection class, so [confidence] belongs to a sign. */
        val isTopSign: Boolean,
    )

    init {
        try {
            spec.requireCompatible(
                inputShape = interpreter.getInputTensor(0).shape(),
                outputShape = interpreter.getOutputTensor(0).shape(),
            )
        } catch (e: ModelSpecException) {
            interpreter.close()
            throw e
        }
        logSetup()
    }

    private fun loadInterpreter(context: Context): Interpreter {
        try {
            return Interpreter(mapAsset(context, spec.modelAsset))
        } catch (e: Exception) {
            // Typical cause: an op version newer than the bundled TFLite runtime supports.
            throw ModelSpecException("No se pudo cargar assets/${spec.modelAsset}: ${e.message}", e)
        }
    }

    private fun mapAsset(context: Context, assetName: String): MappedByteBuffer =
        context.assets.openFd(assetName).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
            }
        }

    /** Volcado único al arrancar: qué modelo y config se cargaron y qué formas tiene el intérprete. */
    private fun logSetup() {
        Log.d(TAG, "================ SIGNACLASSIFIER: ARRANQUE ================")
        Log.d(TAG, "Modelo             : assets/${spec.modelAsset}")
        Log.d(TAG, "Config             : assets/${ModelSpec.CONFIG_ASSET}")
        Log.d(TAG, "Tensor entrada     : ${interpreter.getInputTensor(0).shape().contentToString()}")
        Log.d(TAG, "Tensor salida      : ${interpreter.getOutputTensor(0).shape().contentToString()}")
        Log.d(TAG, "Etiquetas (${spec.labels.size}) : ${spec.labels}")
        Log.d(TAG, "indice_rechazo     : ${spec.rejectionIndex} (${spec.labels[spec.rejectionIndex]})")
        Log.d(TAG, "Umbral global      : ${spec.globalThreshold}${if (spec.globalThreshold == 0f) " (argmax puro)" else ""}")
        Log.d(TAG, "umbral_por_letra (${spec.thresholds.size}): ${spec.thresholds}")
        Log.d(TAG, "k (suavizado)      : $k")
        Log.d(TAG, "===========================================================")
    }

    fun classify(landmarks: FloatArray): PredictionResult {
        val startTime = System.currentTimeMillis()
        frameCounter++

        val input = arrayOf(landmarks)
        val output = Array(1) { FloatArray(spec.labels.size) }
        interpreter.run(input, output)

        val probabilities = output[0]
        val maxIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
        val maxProb = probabilities[maxIndex]
        val rawLabel = spec.labels[maxIndex]
        val threshold = spec.thresholdFor(rawLabel)

        // Lógica de rechazo (clase de rechazo o bajo umbral)
        val isRecognized = maxIndex != spec.rejectionIndex && maxProb >= threshold
        val finalLabel = if (isRecognized) rawLabel else NOT_RECOGNIZED

        // Suavizado temporal (RF07)
        predictionBuffer.add(finalLabel)
        if (predictionBuffer.size > k) {
            predictionBuffer.removeAt(0)
        }

        // Solo confirmamos si los últimos k frames son iguales
        val smoothedLabel = if (predictionBuffer.size == k && predictionBuffer.all { it == predictionBuffer[0] }) {
            predictionBuffer[0]
        } else {
            STABILIZING
        }

        val inferenceTime = System.currentTimeMillis() - startTime

        if (VERBOSE) {
            dumpFrame(landmarks, probabilities, maxIndex, threshold, isRecognized, finalLabel, smoothedLabel, inferenceTime)
        }

        return PredictionResult(
            label = smoothedLabel,
            confidence = maxProb,
            isRecognized = isRecognized && smoothedLabel != STABILIZING,
            inferenceTime = inferenceTime,
            isTopSign = maxIndex != spec.rejectionIndex,
        )
    }

    private fun dumpFrame(
        landmarks: FloatArray,
        probabilities: FloatArray,
        maxIndex: Int,
        threshold: Float,
        isRecognized: Boolean,
        finalLabel: String,
        smoothedLabel: String,
        inferenceTime: Long
    ) {
        val maxProb = probabilities[maxIndex]
        val rawLabel = spec.labels[maxIndex]
        Log.d(TAG, "===================== FRAME #$frameCounter =====================")

        Log.d(TAG, "[ENTRADA] 63 floats (orden x0,y0,z0, x1,y1,z1, ...):")
        Log.d(TAG, "  " + landmarks.joinToString(", ") { "%.5f".format(it) })
        Log.d(TAG, "  [ENTRADA] " + LandmarkNormalizer.axisStats(landmarks))

        val sum = probabilities.sum()
        Log.d(TAG, "[SALIDA] ${probabilities.size} probabilidades (suma=%.6f, un softmax sano debe dar ~1.0):".format(sum))
        Log.d(TAG, "  " + probabilities.mapIndexed { i, p -> "$i:${spec.labels[i]}=%.4f".format(p) }.joinToString("  "))
        val ranking = probabilities.indices.sortedByDescending { probabilities[it] }.take(5)
        Log.d(TAG, "  [SALIDA] top-5: " + ranking.joinToString("  ") { "${spec.labels[it]}(idx=$it)=%.4f".format(probabilities[it]) })

        val thresholdSource = if (rawLabel in spec.thresholds) "umbral_por_letra[\"$rawLabel\"]" else "umbral global"
        Log.d(TAG, "[GANADOR] indice=$maxIndex etiqueta=\"$rawLabel\" confianza=%.6f".format(maxProb))
        Log.d(TAG, "[UMBRAL] %.6f (%s) | confianza %s umbral".format(threshold, thresholdSource, if (maxProb >= threshold) ">=" else "<"))
        Log.d(TAG, "[DECISION] isRecognized=$isRecognized -> \"$finalLabel\"")
        Log.d(TAG, "[SUAVIZADO] k=$k buffer=$predictionBuffer -> mostrado=\"$smoothedLabel\"  (inferencia ${inferenceTime}ms)")
        Log.i(TAG, "[DIAGNOSTICO] " + diagnose(probabilities, maxIndex, threshold, sum))
    }

    /** Clasifica cada frame en: salida anómala, (a) casi uniforme, (b) gana el rechazo, (c) rechazada por umbral, u OK. */
    private fun diagnose(probabilities: FloatArray, maxIndex: Int, threshold: Float, sum: Float): String {
        val maxProb = probabilities[maxIndex]
        val uniform = 1f / probabilities.size
        return when {
            abs(sum - 1f) > 0.05f ->
                "ANÓMALO: la salida no suma 1 (suma=%.6f). El tensor de salida no es un softmax.".format(sum)
            maxProb < uniform * 1.5f ->
                "CASO (a) CASI UNIFORME: máxima %.4f frente a %.4f de un reparto plano -> revisar la normalización.".format(maxProb, uniform)
            maxIndex == spec.rejectionIndex ->
                "CASO (b) GANA \"${spec.labels[maxIndex]}\" CON %.4f (handedness=%s, espejadoX=%b)."
                    .format(maxProb, LandmarkNormalizer.lastHandedness, LandmarkNormalizer.lastMirrored)
            maxProb < threshold ->
                "CASO (c) \"%s\" GANA CON %.4f PERO EL UMBRAL %.4f LA RECHAZA.".format(spec.labels[maxIndex], maxProb, threshold)
            else ->
                "OK: \"%s\" reconocida con %.4f (umbral %.4f).".format(spec.labels[maxIndex], maxProb, threshold)
        }
    }

    fun close() {
        interpreter.close()
    }

    companion object {
        private const val TAG = "SignaCO_Debug"
        const val NOT_RECOGNIZED = "seña no reconocida"
        const val STABILIZING = "estabilizando..."

        /** Poner en false para silenciar el volcado por frame en Logcat. */
        var VERBOSE = true
    }
}
