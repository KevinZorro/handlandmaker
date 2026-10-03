package com.google.mediapipe.examples.handlandmarker

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

private const val TAG = "SignaCO"
private const val SMOOTHING_FRAMES = 3

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    val specLoad = remember {
        runCatching { ModelSpec.load(context) }.onFailure { Log.e(TAG, "Config del modelo inválido", it) }
    }
    val spec = specLoad.getOrElse { error ->
        MessageScreen("El modelo no se puede usar", error.message ?: error.javaClass.simpleName)
        return
    }

    if (hasCameraPermission) {
        KioskScreen(spec)
    } else {
        MessageScreen(
            title = "Necesitamos la cámara",
            body = "La app usa la cámara frontal para leer las señas de tu mano.",
            actionLabel = "Dar permiso",
            onAction = { launcher.launch(Manifest.permission.CAMERA) },
        )
    }
}

/** Fair demo: the camera fills the screen, the hand is framed, and the result card sits at the bottom. */
@Composable
private fun KioskScreen(spec: ModelSpec) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    val classifierLoad = remember {
        runCatching { SignaClassifier(context, spec, k = SMOOTHING_FRAMES) }
            .onFailure { Log.e(TAG, "No se pudo iniciar el clasificador", it) }
    }
    val classifier = classifierLoad.getOrElse { error ->
        MessageScreen("El modelo no se puede usar", error.message ?: error.javaClass.simpleName)
        return
    }

    var handFrame by remember { mutableStateOf<HandFrame?>(null) }
    var recognition by remember { mutableStateOf(RecognitionUiState()) }

    val helper = remember {
        HandLandmarkerHelper(context, object : HandLandmarkerHelper.LandmarkerListener {
            override fun onError(error: String) { Log.e(TAG, error) }

            override fun onResults(resultBundle: HandLandmarkerHelper.ResultBundle) {
                val now = SystemClock.uptimeMillis()
                val result = resultBundle.results.firstOrNull()
                val landmarks = result?.landmarks()?.firstOrNull()
                if (result == null || landmarks == null) {
                    handFrame = null
                    recognition = RecognitionReducer.reduce(recognition, null, now)
                    return
                }
                handFrame = HandFrame(
                    normalizedXY = FloatArray(landmarks.size * 2) { i ->
                        if (i % 2 == 0) landmarks[i / 2].x() else landmarks[i / 2].y()
                    },
                    imageWidth = resultBundle.inputImageWidth,
                    imageHeight = resultBundle.inputImageHeight,
                )
                val reading = LandmarkNormalizer.normalize(result)?.let { classifier.classify(it).toReading() }
                recognition = RecognitionReducer.reduce(recognition, reading, now)
            }
        })
    }

    DisposableEffect(Unit) {
        onDispose {
            helper.clearHandLandmarker()
            classifier.close()
            cameraExecutor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val pv = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build().also {
                            it.setAnalyzer(cameraExecutor) { img -> helper.detectLiveStream(img, true) }
                        }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                }, ContextCompat.getMainExecutor(ctx))
                pv
            }
        )

        HandOverlay(
            frame = handFrame,
            isSignConfirmed = recognition.isHandVisible && recognition.isSignCurrent,
            modifier = Modifier.fillMaxSize(),
        )

        KioskHeader(
            Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(top = 12.dp)
        )

        ResultCard(
            state = recognition,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 24.dp),
        )
    }
}

private fun SignaClassifier.PredictionResult.toReading(): FrameReading = when {
    isRecognized -> FrameReading.Confirmed(label, confidence)
    label == SignaClassifier.NOT_RECOGNIZED -> FrameReading.Rejected
    else -> FrameReading.Settling(if (isTopSign) confidence else 0f)
}

@Composable
private fun MessageScreen(title: String, body: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Box(
        Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(body, color = KioskColors.TextSecondary, fontSize = 16.sp, textAlign = TextAlign.Center)
            if (actionLabel != null) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) { Text(actionLabel) }
            }
        }
    }
}
