package com.google.mediapipe.examples.handlandmarker

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.io.IOException

/** The bundled model and its config cannot be used together. Always fatal: never degrade silently. */
class ModelSpecException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Contract between the bundled .tflite and its config JSON.
 *
 * The .tflite carries no label metadata, so the output order exists only in the config. A wrong
 * order raises no error at inference time: it silently maps scores to the wrong signs. Everything
 * that can be checked without labelled samples is checked here, and every failure is fatal.
 */
data class ModelSpec(
    val modelAsset: String,
    /** Every model output in output-tensor order, rejection class included. */
    val labels: List<String>,
    val rejectionIndex: Int,
    val thresholds: Map<String, Float>,
    val globalThreshold: Float,
) {
    /** Signs a user can perform, in model order (rejection class excluded). */
    val signLabels: List<String> get() = labels.filterIndexed { index, _ -> index != rejectionIndex }

    fun thresholdFor(label: String): Float = thresholds[label] ?: globalThreshold

    /** Fails unless the interpreter tensors can correspond to this config. */
    fun requireCompatible(inputShape: IntArray, outputShape: IntArray) {
        val expectedInput = intArrayOf(1, FEATURE_COUNT)
        if (!inputShape.contentEquals(expectedInput)) {
            throw ModelSpecException(
                "$modelAsset espera entrada ${inputShape.contentToString()} pero la app produce " +
                    "${expectedInput.contentToString()} (21 landmarks x 3)."
            )
        }
        val expectedOutput = intArrayOf(1, labels.size)
        if (!outputShape.contentEquals(expectedOutput)) {
            throw ModelSpecException(
                "$modelAsset produce salida ${outputShape.contentToString()} pero $CONFIG_ASSET declara " +
                    "${labels.size} etiquetas ${expectedOutput.contentToString()}. El config no corresponde a este modelo."
            )
        }
    }

    // Gson target. Nullable fields so a missing key is reported by name instead of surfacing as an NPE.
    private class RawConfig(
        val modelo: String?,
        val etiquetas: List<String>?,
        val indice_rechazo: Int?,
        val umbral_por_letra: Map<String, Float>?,
        val umbral_global_fallback: Float?,
    )

    companion object {
        const val CONFIG_ASSET = "config_modelo_final.json"
        const val REJECTION_LABEL = "no_es_seña"
        const val FEATURE_COUNT = 63

        // The current config ships no calibrated thresholds ("modo objetivo"): the decision is the plain argmax.
        private const val ARGMAX_THRESHOLD = 0f

        fun load(context: Context): ModelSpec {
            val json = try {
                context.assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
            } catch (e: IOException) {
                throw ModelSpecException("No se pudo leer assets/$CONFIG_ASSET.", e)
            }
            return fromJson(json)
        }

        fun fromJson(json: String): ModelSpec {
            val raw = try {
                Gson().fromJson(json, RawConfig::class.java)
            } catch (e: JsonParseException) {
                throw ModelSpecException("$CONFIG_ASSET no es JSON válido.", e)
            } ?: throw ModelSpecException("$CONFIG_ASSET está vacío.")

            val modelAsset = raw.modelo?.takeIf { it.isNotBlank() } ?: missing("modelo")
            val labels = raw.etiquetas?.takeIf { it.isNotEmpty() } ?: missing("etiquetas")
            val rejectionIndex = raw.indice_rechazo ?: missing("indice_rechazo")
            val thresholds = raw.umbral_por_letra.orEmpty()

            requireUniqueLabels(labels)
            requireRejectionLast(labels, rejectionIndex)
            requireKnownThresholdLabels(thresholds.keys, labels, rejectionIndex)

            return ModelSpec(
                modelAsset = modelAsset,
                labels = labels,
                rejectionIndex = rejectionIndex,
                thresholds = thresholds,
                globalThreshold = raw.umbral_global_fallback ?: ARGMAX_THRESHOLD,
            )
        }

        private fun missing(key: String): Nothing = throw ModelSpecException("Falta '$key' en $CONFIG_ASSET.")

        private fun requireUniqueLabels(labels: List<String>) {
            val duplicates = labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (duplicates.isNotEmpty()) {
                throw ModelSpecException("Etiquetas repetidas en $CONFIG_ASSET: $duplicates.")
            }
        }

        private fun requireRejectionLast(labels: List<String>, rejectionIndex: Int) {
            if (rejectionIndex != labels.lastIndex) {
                throw ModelSpecException(
                    "indice_rechazo=$rejectionIndex pero el rechazo debe ser la última posición (${labels.lastIndex})."
                )
            }
            if (labels[rejectionIndex] != REJECTION_LABEL) {
                throw ModelSpecException(
                    "La etiqueta en indice_rechazo=$rejectionIndex es '${labels[rejectionIndex]}', se esperaba '$REJECTION_LABEL'."
                )
            }
        }

        // A threshold for a sign the model does not output means the config was assembled from another model.
        private fun requireKnownThresholdLabels(thresholdLabels: Set<String>, labels: List<String>, rejectionIndex: Int) {
            val unknown = thresholdLabels - labels.filterIndexed { index, _ -> index != rejectionIndex }.toSet()
            if (unknown.isNotEmpty()) {
                throw ModelSpecException("umbral_por_letra tiene etiquetas que el modelo no produce: $unknown.")
            }
        }
    }
}
