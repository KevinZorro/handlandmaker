package com.google.mediapipe.examples.handlandmarker

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        HandLandmarkerContent()
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Permiso de cámara necesario")
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text("Conceder Permiso")
                }
            }
        }
    }
}

@Composable
fun HandLandmarkerContent() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    
    var resultBundle by remember { mutableStateOf<HandLandmarkerHelper.ResultBundle?>(null) }
    var fps by remember { mutableStateOf(0) }
    var lastFpsTimestamp by remember { mutableStateOf(0L) }
    var frameCount by remember { mutableStateOf(0) }

    val helper = remember {
        HandLandmarkerHelper(
            context = context,
            handLandmarkerHelperListener = object : HandLandmarkerHelper.LandmarkerListener {
                override fun onError(error: String) {
                    Log.e("HandLandmarker", error)
                }

                override fun onResults(bundle: HandLandmarkerHelper.ResultBundle) {
                    resultBundle = bundle
                    
                    // Calcular FPS
                    frameCount++
                    val now = System.currentTimeMillis()
                    if (now - lastFpsTimestamp >= 1000) {
                        fps = frameCount
                        frameCount = 0
                        lastFpsTimestamp = now
                    }
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            helper.clearHandLandmarker()
            cameraExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 2. Preview de cámara frontal
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        .also {
                            it.setAnalyzer(cameraExecutor) { imageProxy ->
                                helper.detectLiveStream(imageProxy, isFrontCamera = true)
                            }
                        }

                    val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                    } catch (exc: Exception) {
                        Log.e("HandLandmarker", "Use case binding failed", exc)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        // 3. Dibujar landmarks
        resultBundle?.let { bundle ->
            HandLandmarksOverlay(bundle)
        }

        // 4. Overlay de estadísticas
        StatsOverlay(
            fps = fps,
            inferenceTime = resultBundle?.inferenceTime ?: 0L,
            isHandDetected = (resultBundle?.results?.firstOrNull()?.landmarks()?.isNotEmpty() == true)
        )

        // 5. Botón para Logcat
        LogButton(resultBundle)
    }
}

@Composable
fun HandLandmarksOverlay(bundle: HandLandmarkerHelper.ResultBundle) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val result = bundle.results.firstOrNull() ?: return@Canvas
        val landmarks = result.landmarks().firstOrNull() ?: return@Canvas

        // Dibujar conexiones
        HandLandmarker.HAND_CONNECTIONS.forEach { connection ->
            val start = landmarks[connection.start()]
            val end = landmarks[connection.end()]

            drawLine(
                color = Color.Green,
                start = Offset(start.x() * size.width, start.y() * size.height),
                end = Offset(end.x() * size.width, end.y() * size.height),
                strokeWidth = 5f
            )
        }

        // Dibujar puntos
        landmarks.forEach { landmark ->
            drawCircle(
                color = Color.Red,
                radius = 8f,
                center = Offset(landmark.x() * size.width, landmark.y() * size.height)
            )
        }
    }
}

@Composable
fun BoxScope.StatsOverlay(fps: Int, inferenceTime: Long, isHandDetected: Boolean) {
    Column(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(16.dp)
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(8.dp)
    ) {
        Text("FPS: $fps", color = Color.White)
        Text("Inferencia: ${inferenceTime}ms", color = Color.White)
        Text("Mano detectada: ${if (isHandDetected) "SÍ" else "NO"}", color = Color.White)
    }
}

@Composable
fun BoxScope.LogButton(bundle: HandLandmarkerHelper.ResultBundle?) {
    Button(
        onClick = {
            bundle?.results?.firstOrNull()?.landmarks()?.firstOrNull()?.let { landmarks ->
                Log.d("SignaCO_Spike", "--- Landmark Data ---")
                landmarks.forEachIndexed { index, landmark ->
                    Log.d("SignaCO_Spike", "Point $index: x=${landmark.x()}, y=${landmark.y()}, z=${landmark.z()}")
                }
            } ?: Log.d("SignaCO_Spike", "No hand detected to log")
        },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(32.dp)
    ) {
        Text("Loggear Coordenadas")
    }
}
