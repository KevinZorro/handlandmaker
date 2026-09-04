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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Lista de clases editable: Abecedario A-Z + NN (No seña)
val SIGN_CLASSES = ('A'..'Z').map { it.toString() } + "NN"

sealed class AppState {
    object Setup : AppState()
    data class Recording(
        val participantId: String,
        val condition: String,
        val currentClassIndex: Int = 0
    ) : AppState()
}

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

    var appState by remember { mutableStateOf<AppState>(AppState.Setup) }

    if (!hasCameraPermission) {
        PermissionDeniedScreen { launcher.launch(Manifest.permission.CAMERA) }
    } else {
        when (val state = appState) {
            is AppState.Setup -> SetupScreen(onStart = { id, cond, initialIndex ->
                appState = AppState.Recording(id, cond, initialIndex)
            })
            is AppState.Recording -> RecordingScreen(
                state = state,
                onBackToSetup = { appState = AppState.Setup },
                onUpdateState = { newState -> appState = newState }
            )
        }
    }
}

@Composable
fun PermissionDeniedScreen(onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Permiso de cámara necesario")
            Button(onClick = onRetry) { Text("Conceder Permiso") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onStart: (String, String, Int) -> Unit) {
    var id by remember { mutableStateOf("") }
    var condition by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SignaCO: Configuración") }) }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("ID Participante (P01, etc)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = condition, onValueChange = { condition = it }, label = { Text("Condición (Fondo, Luz, etc)") }, modifier = Modifier.fillMaxWidth())

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = SIGN_CLASSES[selectedIndex],
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Letra inicial") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    SIGN_CLASSES.forEachIndexed { index, label ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                selectedIndex = index
                                expanded = false
                            }
                        )
                    }
                }
            }

            Button(
                onClick = { if (id.isNotBlank() && condition.isNotBlank()) onStart(id, condition, selectedIndex) },
                modifier = Modifier.fillMaxWidth(),
                enabled = id.isNotBlank() && condition.isNotBlank()
            ) {
                Text("Iniciar Sesión de Grabación")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    state: AppState.Recording,
    onBackToSetup: () -> Unit,
    onUpdateState: (AppState.Recording) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val logger = remember { SessionLogger(context) }
    
    // Clasificador TFLite
    val classifier = remember { SignaClassifier(context, k = 3) }
    
    var isRecording by remember { mutableStateOf(false) }
    var samplesInTake by remember { mutableIntStateOf(0) }
    var discardedFrames by remember { mutableIntStateOf(0) }
    var isHandDetected by remember { mutableStateOf(false) }
    var currentResultBundle by remember { mutableStateOf<HandLandmarkerHelper.ResultBundle?>(null) }
    
    // Estado de validación
    var classifierResult by remember { mutableStateOf<SignaClassifier.PredictionResult?>(null) }
    var hits by remember { mutableIntStateOf(0) }
    var misses by remember { mutableIntStateOf(0) }
    var testModeEnabled by remember { mutableStateOf(true) }
    var lastProcessedLandmarks by remember { mutableStateOf<FloatArray?>(null) }
    
    val currentClassName = SIGN_CLASSES[state.currentClassIndex]
    var takeNumber by remember { mutableIntStateOf(1) }
    var menuExpanded by remember { mutableStateOf(false) }

    val helper = remember {
        HandLandmarkerHelper(context, object : HandLandmarkerHelper.LandmarkerListener {
            override fun onError(error: String) { Log.e("SignaCO", error) }
            override fun onResults(resultBundle: HandLandmarkerHelper.ResultBundle) {
                currentResultBundle = resultBundle
                val handVisible = resultBundle.results.firstOrNull()?.landmarks()?.isNotEmpty() == true
                isHandDetected = handVisible

                if (handVisible) {
                    val normalized = LandmarkNormalizer.normalize(resultBundle.results.first())
                    if (normalized != null) {
                        lastProcessedLandmarks = normalized
                        val prediction = classifier.classify(normalized)
                        classifierResult = prediction
                        
                        // Si estamos grabando para dataset
                        if (isRecording) {
                            logger.logFrame(resultBundle.results.first(), normalized)
                            samplesInTake++
                        }
                        
                        // Lógica de contador de aciertos/fallos en modo test
                        if (testModeEnabled && prediction.label != "estabilizando...") {
                            if (prediction.label == currentClassName) {
                                hits++
                            } else if (prediction.label != "seña no reconocida") {
                                misses++
                            }
                        }
                    }
                } else {
                    classifierResult = null
                    if (isRecording) discardedFrames++
                }
            }
        })
    }

    LaunchedEffect(state.participantId, state.condition) {
        logger.startSession(state.participantId, state.condition)
    }

    DisposableEffect(Unit) {
        onDispose {
            helper.clearHandLandmarker()
            classifier.close()
            cameraExecutor.shutdown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        TextButton(onClick = { menuExpanded = true }) {
                            Text("Gesto: $currentClassName", style = MaterialTheme.typography.titleLarge)
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            SIGN_CLASSES.forEachIndexed { index, label ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        onUpdateState(state.copy(currentClassIndex = index))
                                        takeNumber = 1
                                        samplesInTake = 0
                                        discardedFrames = 0
                                        isRecording = false
                                        hits = 0
                                        misses = 0
                                        menuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackToSetup) { Icon(Icons.Default.ArrowBack, null) }
                },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            text = if (testModeEnabled) "VALIDACIÓN" else "CAPTURA",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (testModeEnabled) Color.Cyan else Color.Yellow
                        )
                        Switch(
                            checked = testModeEnabled,
                            onCheckedChange = { 
                                testModeEnabled = it
                                isRecording = false // Stop recording if switching modes
                            }
                        )
                        
                        if (!testModeEnabled) {
                            IconButton(onClick = { logger.shareCsv() }) {
                                Icon(Icons.Default.Share, contentDescription = "Exportar CSV")
                            }
                        }
                    }
                }
            )
        }
    ) { pad ->
        Box(modifier = Modifier.padding(pad).fillMaxSize()) {
            // Camera Preview
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

            // Landmarks Overlay
            currentResultBundle?.let { HandLandmarksOverlay(it) }

            // INFO OVERLAY (Real-time Prediction)
            classifierResult?.let { result ->
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(0.6f), MaterialTheme.shapes.medium)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = result.label.uppercase(),
                        style = MaterialTheme.typography.displayLarge,
                        color = if (result.isRecognized) Color.Green else Color.White
                    )
                    Text(
                        text = "Confianza: ${(result.confidence * 100).toInt()}%",
                        color = Color.White
                    )
                    Text(
                        text = "Inferencia TFLite: ${result.inferenceTime}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            }

            // Stats & Controls
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Stats
                Surface(color = Color.Black.copy(0.6f), shape = MaterialTheme.shapes.medium) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        if (testModeEnabled) {
                            Text("TEST: $currentClassName | Aciertos: $hits | Fallos: $misses", color = Color.Cyan)
                        } else {
                            Text("Muestras: $samplesInTake | Descartados: $discardedFrames", color = Color.White)
                        }
                        Text("Detección: ${if (isHandDetected) "OK" else "NO"}", 
                             color = if (isHandDetected) Color.Green else Color.Red)
                    }
                }

                // Bottom Controls
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Log 63 floats button
                        IconButton(
                            onClick = {
                                lastProcessedLandmarks?.let { vec ->
                                    Log.d("SignaCO_Vector", "--- 63 Floats Vector ---")
                                    Log.d("SignaCO_Vector", vec.joinToString(", "))
                                }
                            },
                            modifier = Modifier.background(Color.Gray.copy(0.8f), MaterialTheme.shapes.extraLarge)
                        ) { Icon(Icons.Default.Check, "Log Vector", tint = Color.White) }

                        // Record / Reset button
                        Button(
                            onClick = { 
                                if (testModeEnabled) {
                                    hits = 0
                                    misses = 0
                                } else {
                                    if (!isRecording) {
                                        logger.startTake(currentClassName, takeNumber)
                                        samplesInTake = 0
                                        discardedFrames = 0
                                    } else {
                                        takeNumber++
                                    }
                                    isRecording = !isRecording 
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecording) Color.Red else MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.height(56.dp).width(200.dp)
                        ) {
                            Text(
                                if (testModeEnabled) "REINICIAR CONTADORES" 
                                else if (isRecording) "DETENER GRABACIÓN" 
                                else "INICIAR GRABACIÓN"
                            )
                        }

                        // Next class
                        IconButton(
                            onClick = {
                                if (state.currentClassIndex < SIGN_CLASSES.size - 1) {
                                    onUpdateState(state.copy(currentClassIndex = state.currentClassIndex + 1))
                                    takeNumber = 1
                                    samplesInTake = 0
                                    discardedFrames = 0
                                    isRecording = false
                                    hits = 0
                                    misses = 0
                                }
                            },
                            modifier = Modifier.background(Color.Green.copy(0.8f), MaterialTheme.shapes.extraLarge)
                        ) { Icon(Icons.Default.ArrowForward, "Siguiente", tint = Color.Black) }
                    }
                    
                    Text("Participante: ${state.participantId} | Condición: ${state.condition}", 
                         style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun HandLandmarksOverlay(bundle: HandLandmarkerHelper.ResultBundle) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val landmarks = bundle.results.firstOrNull()?.landmarks()?.firstOrNull() ?: return@Canvas
        HandLandmarker.HAND_CONNECTIONS.forEach { connection ->
            val start = landmarks[connection.start()]
            val end = landmarks[connection.end()]
            drawLine(Color.Green, Offset(start.x() * size.width, start.y() * size.height), Offset(end.x() * size.width, end.y() * size.height), strokeWidth = 4f)
        }
        landmarks.forEach { drawCircle(Color.Red, radius = 6f, center = Offset(it.x() * size.width, it.y() * size.height)) }
    }
}
