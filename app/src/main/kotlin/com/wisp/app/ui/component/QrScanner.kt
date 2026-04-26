package com.wisp.app.ui.component

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * Reusable QR code scanner. Requests camera permission, shows a CameraX preview
 * with a viewfinder overlay, and invokes [onResult] exactly once with the raw
 * decoded barcode value.
 */
@Composable
fun QrScanner(
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
    promptText: String? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!hasCameraPermission) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Camera permission required",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }) {
                        Text("Grant permission")
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
            ) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }

                            val options = BarcodeScannerOptions.Builder()
                                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                .build()
                            val scanner = BarcodeScanning.getClient(options)

                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                                @androidx.camera.core.ExperimentalGetImage
                                val mediaImage = imageProxy.image
                                if (mediaImage != null && !scanned) {
                                    val inputImage = InputImage.fromMediaImage(
                                        mediaImage, imageProxy.imageInfo.rotationDegrees
                                    )
                                    scanner.process(inputImage)
                                        .addOnSuccessListener { barcodes ->
                                            val value = barcodes.firstOrNull()?.rawValue
                                            if (value != null && !scanned) {
                                                scanned = true
                                                onResult(value)
                                            }
                                        }
                                        .addOnCompleteListener { imageProxy.close() }
                                } else {
                                    imageProxy.close()
                                }
                            }

                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis
                                )
                            } catch (e: Exception) {
                                Log.e("QrScanner", "Camera bind failed", e)
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                Box(
                    modifier = Modifier
                        .size(250.dp)
                        .align(Alignment.Center)
                        .background(Color.Transparent)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeW = 3.dp.toPx()
                        val cornerLen = 30.dp.toPx()
                        val color = Color.White
                        drawLine(color, Offset(0f, 0f), Offset(cornerLen, 0f), strokeWidth = strokeW)
                        drawLine(color, Offset(0f, 0f), Offset(0f, cornerLen), strokeWidth = strokeW)
                        drawLine(color, Offset(size.width, 0f), Offset(size.width - cornerLen, 0f), strokeWidth = strokeW)
                        drawLine(color, Offset(size.width, 0f), Offset(size.width, cornerLen), strokeWidth = strokeW)
                        drawLine(color, Offset(0f, size.height), Offset(cornerLen, size.height), strokeWidth = strokeW)
                        drawLine(color, Offset(0f, size.height), Offset(0f, size.height - cornerLen), strokeWidth = strokeW)
                        drawLine(color, Offset(size.width, size.height), Offset(size.width - cornerLen, size.height), strokeWidth = strokeW)
                        drawLine(color, Offset(size.width, size.height), Offset(size.width, size.height - cornerLen), strokeWidth = strokeW)
                    }
                }
            }
        }

        if (promptText != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                promptText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
