package com.google.mediapipe.examples.handlandmarker

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class SignaClassifier(context: Context, private val k: Int = 3) {

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var thresholds: Map<String, Float> = emptyMap()
    private var rejectionIndex: Int = 27

    // Buffer para suavizado temporal (RF07)
    private val predictionBuffer = mutableListOf<String>()

    data class PredictionResult(
        val label: String,
        val confidence: Float,
        val isRecognized: Boolean,
        val inferenceTime: Long
    )

    data class Config(
        val etiquetas: List<String>,
        val indice_rechazo: Int,
        val umbral_por_letra: Map<String, Float>
    )

    init {
        loadModel(context)
        loadConfig(context)
    }

    private fun loadModel(context: Context) {
        try {
            val assetFileDescriptor = context.assets.openFd("signaco_abecedario_rechazo.tflite")
            val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            
            interpreter = Interpreter(buffer)
            Log.d("SignaClassifier", "Model loaded successfully")
        } catch (e: Exception) {
            Log.e("SignaClassifier", "Error loading model: ${e.message}", e)
        }
    }

    private fun loadConfig(context: Context) {
        try {
            val jsonString = context.assets.open("config_rechazo.json").bufferedReader().use { it.readText() }
            val config = Gson().fromJson(jsonString, Config::class.java)
            labels = config.etiquetas
            thresholds = config.umbral_por_letra
            rejectionIndex = config.indice_rechazo
            Log.d("SignaClassifier", "Config loaded successfully")
        } catch (e: Exception) {
            Log.e("SignaClassifier", "Error loading config: ${e.message}", e)
            // Fallback default labels if config fails
            labels = ('A'..'Z').map { it.toString() } + "NN"
        }
    }

    fun classify(landmarks: FloatArray): PredictionResult {
        val startTime = System.currentTimeMillis()
        
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
        val threshold = thresholds[rawLabel] ?: 0.1f
        
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
        
        return PredictionResult(
            label = smoothedLabel,
            confidence = maxProb,
            isRecognized = isRecognized && smoothedLabel != "estabilizando...",
            inferenceTime = inferenceTime
        )
    }

    fun close() {
        interpreter?.close()
    }
}
