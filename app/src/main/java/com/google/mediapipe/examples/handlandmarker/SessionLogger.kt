package com.google.mediapipe.examples.handlandmarker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class SessionLogger(private val context: Context) {

    private var currentFile: File? = null
    private var participantId: String = ""
    private var condition: String = ""
    private var currentClass: String = ""
    private var takeNumber: Int = 0

    fun startSession(participantId: String, condition: String) {
        try {
            this.participantId = participantId
            this.condition = condition
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "SignaCO_${participantId}_${condition}_$timestamp.csv"
            currentFile = File(context.getExternalFilesDir(null), fileName)
            
            // Escribir encabezado
            val header = StringBuilder().apply {
                append("clase,participant_id,condicion,numero_toma,timestamp,handedness")
                // Crudos
                for (i in 0..20) append(",x$i,y$i,z$i")
                // Normalizados
                for (i in 0..20) append(",nx$i,ny$i,nz$i")
                append("\n")
            }.toString()
            
            FileOutputStream(currentFile, false).use { it.write(header.toByteArray()) }
            Log.d("SessionLogger", "Session started: ${currentFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e("SessionLogger", "Error starting session: ${e.message}", e)
        }
    }

    fun startTake(className: String, takeNum: Int) {
        this.currentClass = className
        this.takeNumber = takeNum
    }

    fun logFrame(result: HandLandmarkerResult, normalized: FloatArray?) {
        try {
            val file = currentFile ?: return
            val landmarks = result.landmarks().firstOrNull() ?: return
            val handedness = result.handedness().firstOrNull()?.firstOrNull()?.categoryName() ?: "Right"
            val norm = normalized ?: return

            val row = StringBuilder().apply {
                append("$currentClass,$participantId,$condition,$takeNumber,${System.currentTimeMillis()},$handedness")
                // Escribir crudos
                landmarks.forEach { append(",${it.x()},${it.y()},${it.z()}") }
                // Escribir normalizados
                norm.forEach { append(",$it") }
                append("\n")
            }.toString()

            FileOutputStream(file, true).use { it.write(row.toByteArray()) }
        } catch (e: Exception) {
            Log.e("SessionLogger", "Error logging frame: ${e.message}", e)
        }
    }

    fun shareCsv() {
        val file = currentFile ?: return
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "SignaCO Dataset: ${file.name}")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartir Dataset CSV"))
    }

    fun discardLastTake(takeNum: Int) {
        // En una implementación simple, no borramos del CSV pero podríamos marcarlo.
        // Dado que es una herramienta interna rápida, simplemente logueamos que se descartó.
        // Opcionalmente se podría re-escribir el archivo filtrando, pero por ahora solo logueamos.
        Log.d("SessionLogger", "Take $takeNum discarded for class $currentClass")
    }
}
