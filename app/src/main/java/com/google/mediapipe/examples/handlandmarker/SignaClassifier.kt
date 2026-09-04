package com.google.mediapipe.examples.handlandmarker

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel
import kotlin.math.abs

class SignaClassifier(context: Context, private val k: Int = 3) {

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var thresholds: Map<String, Float> = emptyMap()
    private var rejectionIndex: Int = 27
    private var globalFallback: Float = DEFAULT_THRESHOLD

    // Procedencia de la configuración, para poder decir en el log de dónde salió cada número.
    private var configSource: String = "SIN CARGAR"
    private var modelSource: String = "SIN CARGAR"
    private var frameCounter: Long = 0

    // Buffer para suavizado temporal (RF07)
    private val predictionBuffer = mutableListOf<String>()

    data class PredictionResult(
        val label: String,
        val confidence: Float,
        val isRecognized: Boolean,
        val inferenceTime: Long
    )

    data class Config(
        val etiquetas: List<String>?,
        val indice_rechazo: Int?,
        val umbral_por_letra: Map<String, Float>?,
        val umbral_global_fallback: Float?
    )

    init {
        loadModel(context)
        loadConfig(context)
        logSetup()
    }

    private fun loadModel(context: Context) {
        try {
            val assetFileDescriptor = context.assets.openFd(MODEL_ASSET)
            val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            interpreter = Interpreter(buffer)
            modelSource = "assets/$MODEL_ASSET ($declaredLength bytes)"
            Log.d(TAG, "Modelo cargado OK desde $modelSource")
        } catch (e: Exception) {
            modelSource = "FALLO: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "ERROR cargando el modelo: ${e.message}", e)
        }
    }

    private fun loadConfig(context: Context) {
        try {
            val jsonString = context.assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
            val config = Gson().fromJson(jsonString, Config::class.java)

            val parsedLabels = config?.etiquetas
            if (parsedLabels.isNullOrEmpty()) {
                throw IllegalStateException("'etiquetas' vacío o ausente en $CONFIG_ASSET")
            }
            labels = parsedLabels
            thresholds = config.umbral_por_letra ?: emptyMap()
            rejectionIndex = config.indice_rechazo ?: labels.size
            globalFallback = config.umbral_global_fallback ?: DEFAULT_THRESHOLD
            configSource = "assets/$CONFIG_ASSET"
            Log.d(TAG, "Config cargada OK desde $configSource")
        } catch (e: Exception) {
            Log.e(TAG, "ERROR cargando la config: ${e.message}", e)
            // Fallback default labels if config fails
            labels = ('A'..'Z').map { it.toString() } + "NN"
            thresholds = emptyMap()
            rejectionIndex = 27
            globalFallback = DEFAULT_THRESHOLD
            configSource = "FALLBACK EN CÓDIGO (la lectura del JSON falló: ${e.message})"
        }
    }

    /** Volcado único al arrancar: dice si el modelo/config existen y qué formas espera el intérprete. */
    private fun logSetup() {
        Log.d(TAG, "================ SIGNACLASSIFIER: ARRANQUE ================")
        Log.d(TAG, "Modelo            : $modelSource")
        Log.d(TAG, "Interpreter        : ${if (interpreter != null) "INSTANCIADO" else "NULO -> run() se salta y la salida queda en CEROS"}")
        interpreter?.let {
            try {
                val inT = it.getInputTensor(0)
                val outT = it.getOutputTensor(0)
                Log.d(TAG, "Tensor entrada     : shape=${inT.shape().joinToString("x")} dtype=${inT.dataType()}")
                Log.d(TAG, "Tensor salida      : shape=${outT.shape().joinToString("x")} dtype=${outT.dataType()}")
            } catch (e: Exception) {
                Log.e(TAG, "No se pudieron leer las formas de los tensores: ${e.message}", e)
            }
        }
        Log.d(TAG, "Config             : $configSource")
        Log.d(TAG, "Etiquetas (${labels.size})   : $labels")
        Log.d(TAG, "Buffer de salida   : ${labels.size} etiquetas + 1 rechazo = ${labels.size + 1} clases")
        Log.d(TAG, "indice_rechazo     : $rejectionIndex")
        Log.d(TAG, "umbral_global_fallback: $globalFallback")
        Log.d(TAG, "umbral_por_letra (${thresholds.size}): $thresholds")
        Log.d(TAG, "k (suavizado)      : $k")
        Log.d(TAG, "===========================================================")
    }

    /**
     * Devuelve el umbral aplicable a [label] junto con una descripción de su procedencia,
     * para que el log pueda responder "¿contra qué umbral se compara y de dónde salió?".
     */
    private fun thresholdFor(label: String): Pair<Float, String> {
        val perLetter = thresholds[label]
        return when {
            perLetter != null ->
                perLetter to "umbral_por_letra[\"$label\"] de $configSource"
            configSource.startsWith("assets") ->
                globalFallback to "umbral_global_fallback de $configSource (no hay entrada para \"$label\")"
            else ->
                globalFallback to "constante DEFAULT_THRESHOLD del código ($configSource)"
        }
    }

    fun classify(landmarks: FloatArray): PredictionResult {
        val startTime = System.currentTimeMillis()
        frameCounter++

        val input = arrayOf(landmarks)
        val output = Array(1) { FloatArray(labels.size + 1) } // 28 clases en total

        interpreter?.run(input, output)

        val probabilities = output[0]
        var maxIndex = -1
        var maxProb = -1f

        for (i in probabilities.indices) {
            if (probabilities[i] > maxProb) {
                maxProb = probabilities[i]
                maxIndex = i
            }
        }

        val rawLabel = if (maxIndex < labels.size) labels[maxIndex] else "Rechazo"
        val (threshold, thresholdOrigin) = thresholdFor(rawLabel)

        // Lógica de rechazo (Clase 27 o bajo umbral)
        val isRecognized = maxIndex != rejectionIndex && maxProb >= threshold
        val finalLabel = if (isRecognized) rawLabel else "seña no reconocida"

        // Suavizado temporal (RF07)
        predictionBuffer.add(finalLabel)
        if (predictionBuffer.size > k) {
            predictionBuffer.removeAt(0)
        }

        // Solo confirmamos si los últimos k frames son iguales
        val smoothedLabel = if (predictionBuffer.size == k && predictionBuffer.all { it == predictionBuffer[0] }) {
            predictionBuffer[0]
        } else {
            "estabilizando..."
        }

        val inferenceTime = System.currentTimeMillis() - startTime

        if (VERBOSE) {
            dumpFrame(
                landmarks, probabilities, maxIndex, maxProb, rawLabel,
                threshold, thresholdOrigin, isRecognized, finalLabel, smoothedLabel, inferenceTime
            )
        }

        return PredictionResult(
            label = smoothedLabel,
            confidence = maxProb,
            isRecognized = isRecognized && smoothedLabel != "estabilizando...",
            inferenceTime = inferenceTime
        )
    }

    private fun dumpFrame(
        landmarks: FloatArray,
        probabilities: FloatArray,
        maxIndex: Int,
        maxProb: Float,
        rawLabel: String,
        threshold: Float,
        thresholdOrigin: String,
        isRecognized: Boolean,
        finalLabel: String,
        smoothedLabel: String,
        inferenceTime: Long
    ) {
        Log.d(TAG, "===================== FRAME #$frameCounter =====================")

        // --- 1. Vector de 63 floats normalizados que entra al modelo ---
        Log.d(TAG, "[ENTRADA] 63 floats (orden x0,y0,z0, x1,y1,z1, ...):")
        Log.d(TAG, "  " + landmarks.joinToString(", ") { "%.5f".format(it) })
        Log.d(TAG, "  [ENTRADA] " + LandmarkNormalizer.axisStats(landmarks))
        Log.d(
            TAG,
            "  [ENTRADA] muñeca normalizada=(%.5f, %.5f, %.5f) (debe ser 0,0,0) | todoCeros=%b"
                .format(landmarks[0], landmarks[1], landmarks[2], landmarks.all { it == 0f })
        )

        // --- 2. Las 28 probabilidades completas ---
        val suma = probabilities.sum()
        Log.d(TAG, "[SALIDA] ${probabilities.size} probabilidades (suma=%.6f, un softmax sano debe dar ~1.0):".format(suma))
        Log.d(TAG, "  " + probabilities.mapIndexed { i, p -> "$i:${nameOf(i)}=%.4f".format(p) }.joinToString("  "))

        val ranking = probabilities.indices.sortedByDescending { probabilities[it] }.take(5)
        Log.d(TAG, "  [SALIDA] top-5: " + ranking.joinToString("  ") { "${nameOf(it)}(idx=$it)=%.4f".format(probabilities[it]) })

        // --- 3. Ganador y confianza ---
        Log.d(TAG, "[GANADOR] indice=$maxIndex etiqueta=\"$rawLabel\" confianza=%.6f (%.2f%%)".format(maxProb, maxProb * 100f))

        // --- 4. Umbral aplicado y su procedencia ---
        Log.d(TAG, "[UMBRAL] valor=%.6f  <- %s".format(threshold, thresholdOrigin))
        Log.d(TAG, "[UMBRAL] comparacion: confianza %.6f %s umbral %.6f".format(maxProb, if (maxProb >= threshold) ">=" else "<", threshold))
        Log.d(TAG, "[RECHAZO] indice_rechazo=$rejectionIndex (de $configSource) | ¿ganó la clase de rechazo? ${maxIndex == rejectionIndex}")
        Log.d(TAG, "[DECISION] isRecognized=$isRecognized -> \"$finalLabel\"")
        Log.d(TAG, "[SUAVIZADO] k=$k buffer=$predictionBuffer -> mostrado=\"$smoothedLabel\"  (inferencia ${inferenceTime}ms)")

        // --- 5. Diagnóstico automático: cuál de los tres casos es ---
        Log.i(TAG, "[DIAGNOSTICO] " + diagnose(probabilities, maxIndex, maxProb, rawLabel, threshold, suma))
    }

    /** Clasifica automáticamente el fallo en uno de los casos (a) / (b) / (c). */
    private fun diagnose(
        probabilities: FloatArray,
        maxIndex: Int,
        maxProb: Float,
        rawLabel: String,
        threshold: Float,
        suma: Float
    ): String {
        val uniforme = 1f / probabilities.size
        return when {
            interpreter == null ->
                "CASO (0) LA INFERENCIA NO CORRIÓ: interpreter=NULO, así que run() se saltó y la salida quedó en ceros. El .tflite no se pudo cargar ($modelSource). No es un problema de normalización ni de umbral."

            abs(suma) < 1e-4f ->
                "CASO (0) LA INFERENCIA NO PRODUJO SALIDA: las %d probabilidades son todas cero (suma=%.6f) aunque el interpreter existe. Revisar el shape del buffer de salida frente al del tensor del modelo."
                    .format(probabilities.size, suma)

            abs(suma - 1f) > 0.05f ->
                "ANÓMALO: la salida no suma 1 (suma=%.6f). El tamaño del buffer de salida (${probabilities.size}) o el layout del tensor no coinciden con el modelo."
                    .format(suma)

            maxProb < uniforme * 1.5f ->
                "CASO (a) DISTRIBUCIÓN CASI UNIFORME: la máxima es %.4f frente a %.4f de un reparto plano. El modelo está recibiendo basura -> revisar la normalización."
                    .format(maxProb, uniforme)

            maxIndex == rejectionIndex ->
                "CASO (b) GANA \"no_es_seña\" CON CONFIANZA %.4f. El vector es válido en forma pero cae fuera de la distribución de entrenamiento -> normalización mal de otra forma, o el espejado (handedness=%s, espejadoX=%b) aplicado al revés."
                    .format(maxProb, LandmarkNormalizer.lastHandedness, LandmarkNormalizer.lastMirrored)

            maxProb < threshold ->
                "CASO (c) LA LETRA \"%s\" GANA CON %.4f PERO EL UMBRAL %.4f LA RECHAZA. El problema es la lectura del config, no la normalización."
                    .format(rawLabel, maxProb, threshold)

            else ->
                "OK: \"%s\" reconocida con %.4f (umbral %.4f).".format(rawLabel, maxProb, threshold)
        }
    }

    private fun nameOf(index: Int): String = when {
        index < labels.size -> labels[index]
        index == rejectionIndex -> "no_es_seña"
        else -> "?$index"
    }

    fun close() {
        interpreter?.close()
    }

    companion object {
        private const val TAG = "SignaCO_Debug"
        private const val MODEL_ASSET = "signaco_abecedario_rechazo.tflite"
        private const val CONFIG_ASSET = "config_rechazo.json"
        private const val DEFAULT_THRESHOLD = 0.1f

        /** Poner en false para silenciar el volcado por frame en Logcat. */
        var VERBOSE = true
    }
}
