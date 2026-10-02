package com.google.mediapipe.examples.handlandmarker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelSpecTest {

    private fun config(
        labels: List<String> = listOf("A", "B", "C", ModelSpec.REJECTION_LABEL),
        rejectionIndex: Int? = labels.lastIndex,
        model: String? = "model.tflite",
        extra: String = "",
    ): String {
        val fields = mutableListOf("\"etiquetas\": [${labels.joinToString { "\"$it\"" }}]")
        rejectionIndex?.let { fields += "\"indice_rechazo\": $it" }
        model?.let { fields += "\"modelo\": \"$it\"" }
        if (extra.isNotEmpty()) fields += extra
        return "{ ${fields.joinToString(", ")} }"
    }

    private fun assertRejected(json: String, messagePart: String) {
        val error = assertThrows(ModelSpecException::class.java) { ModelSpec.fromJson(json) }
        assertTrue("'${error.message}' should mention '$messagePart'", error.message!!.contains(messagePart))
    }

    @Test
    fun shippedConfigMatchesTheBundledModelContract() {
        val spec = ModelSpec.fromJson(File("src/main/assets/${ModelSpec.CONFIG_ASSET}").readText())

        assertTrue(File("src/main/assets/${spec.modelAsset}").isFile)
        assertEquals(36, spec.labels.size)
        assertEquals(35, spec.rejectionIndex)
        assertEquals(35, spec.signLabels.size)
        assertFalse(ModelSpec.REJECTION_LABEL in spec.signLabels)
        assertEquals(listOf("A", "B", "C"), spec.signLabels.take(3))
        assertEquals(listOf("1", "4", "5", "6", "7", "8", "9", "10"), spec.signLabels.takeLast(8))
        // Uncalibrated config: the decision is the plain argmax.
        assertEquals(0f, spec.thresholdFor("A"), 0f)
        spec.requireCompatible(inputShape = intArrayOf(1, 63), outputShape = intArrayOf(1, 36))
    }

    @Test
    fun signLabelsKeepModelOrderAndDropRejection() {
        val spec = ModelSpec.fromJson(config(labels = listOf("NN", "A", "10", ModelSpec.REJECTION_LABEL)))
        assertEquals(listOf("NN", "A", "10"), spec.signLabels)
    }

    @Test
    fun rejectsRejectionIndexThatIsNotLast() {
        assertRejected(config(rejectionIndex = 0), "última posición")
    }

    @Test
    fun rejectsWrongLabelAtRejectionIndex() {
        assertRejected(config(labels = listOf("A", "B", "C")), "se esperaba '${ModelSpec.REJECTION_LABEL}'")
    }

    @Test
    fun rejectsDuplicateLabels() {
        assertRejected(config(labels = listOf("A", "B", "A", ModelSpec.REJECTION_LABEL)), "repetidas")
    }

    @Test
    fun rejectsMissingRequiredKeys() {
        assertRejected(config(model = null), "'modelo'")
        assertRejected(config(rejectionIndex = null), "'indice_rechazo'")
        assertRejected("{ \"modelo\": \"m.tflite\", \"indice_rechazo\": 0 }", "'etiquetas'")
    }

    @Test
    fun rejectsMalformedOrEmptyJson() {
        assertRejected("{ not json", "JSON válido")
        assertRejected("", "vacío")
    }

    @Test
    fun rejectsThresholdsForSignsTheModelDoesNotOutput() {
        assertRejected(config(extra = "\"umbral_por_letra\": {\"A\": 0.2, \"LL\": 0.3}"), "[LL]")
    }

    @Test
    fun usesPerSignThresholdThenGlobalFallback() {
        val spec = ModelSpec.fromJson(
            config(extra = "\"umbral_por_letra\": {\"A\": 0.2}, \"umbral_global_fallback\": 0.4")
        )
        assertEquals(0.2f, spec.thresholdFor("A"), 0f)
        assertEquals(0.4f, spec.thresholdFor("B"), 0f)
    }

    @Test
    fun rejectsModelWhoseOutputSizeDiffersFromConfig() {
        val spec = ModelSpec.fromJson(config())
        spec.requireCompatible(intArrayOf(1, 63), intArrayOf(1, 4))

        val error = assertThrows(ModelSpecException::class.java) {
            spec.requireCompatible(intArrayOf(1, 63), intArrayOf(1, 5))
        }
        assertTrue(error.message!!.contains("no corresponde"))
    }

    @Test
    fun rejectsModelWithUnexpectedInputShape() {
        val spec = ModelSpec.fromJson(config())
        assertThrows(ModelSpecException::class.java) {
            spec.requireCompatible(intArrayOf(1, 42), intArrayOf(1, 4))
        }
    }
}
