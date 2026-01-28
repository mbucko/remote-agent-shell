package com.ras.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ras.R
import com.ras.pairing.PairingState
import com.ras.pairing.QrParseResult
import com.ras.pairing.QrScanner
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    onPaired: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val qrParseError by viewModel.qrParseError.collectAsStateWithLifecycle()

    // Navigate when paired successfully
    LaunchedEffect(state) {
        if (state is PairingState.Authenticated) {
            delay(1500) // Show success state briefly
            onPaired()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Pair with Host",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            when (state) {
                PairingState.Idle,
                PairingState.Scanning -> {
                    CameraPreviewSection(
                        onQrScanned = { viewModel.onQrScanned(it) },
                        qrParseError = qrParseError,
                        onClearError = { viewModel.clearQrError() },
                        modifier = Modifier.weight(1f)
                    )
                }

                is PairingState.QrParsed,
                PairingState.Signaling,
                PairingState.TryingDirect,
                PairingState.DirectSignaling -> {
                    ProgressSection(
                        message = "Connecting to host...",
                        modifier = Modifier.weight(1f)
                    )
                }

                PairingState.NtfySubscribing -> {
                    ProgressSection(
                        message = "Setting up relay connection...",
                        modifier = Modifier.weight(1f)
                    )
                }

                PairingState.NtfyWaitingForAnswer -> {
                    ProgressSection(
                        message = "Waiting for host response...",
                        modifier = Modifier.weight(1f)
                    )
                }

                PairingState.Connecting -> {
                    ProgressSection(
                        message = "Establishing secure connection...",
                        modifier = Modifier.weight(1f)
                    )
                }

                PairingState.Authenticating -> {
                    ProgressSection(
                        message = "Authenticating...",
                        modifier = Modifier.weight(1f)
                    )
                }

                is PairingState.Authenticated -> {
                    SuccessSection(
                        deviceId = (state as PairingState.Authenticated).deviceId,
                        modifier = Modifier.weight(1f)
                    )
                }

                is PairingState.Failed -> {
                    FailureSection(
                        reason = (state as PairingState.Failed).reason,
                        onRetry = { viewModel.retry() },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraPreviewSection(
    onQrScanned: (String) -> Unit,
    qrParseError: QrParseResult.ErrorCode?,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Clear error after a delay
    LaunchedEffect(qrParseError) {
        if (qrParseError != null) {
            delay(3000)
            onClearError()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (hasCameraPermission) {
            QrScannerView(
                onQrScanned = onQrScanned,
                modifier = Modifier
                    .size(280.dp)
                    .clip(RoundedCornerShape(16.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Camera permission required",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Permission")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (qrParseError != null) {
            Text(
                text = getQrParseErrorMessage(qrParseError),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            Text(
                text = "Point camera at QR code on host",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QrScannerView(
    onQrScanned: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var scanner by remember { mutableStateOf<QrScanner?>(null) }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).also { previewView ->
                val qrScanner = QrScanner(
                    context = ctx,
                    lifecycleOwner = lifecycleOwner,
                    previewView = previewView,
                    onQrCodeDetected = onQrScanned
                )
                scanner = qrScanner
                qrScanner.startScanning()
            }
        },
        modifier = modifier
    )

    DisposableEffect(Unit) {
        onDispose {
            scanner?.release()
        }
    }
}

@Composable
private fun ProgressSection(
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SuccessSection(
    deviceId: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Paired successfully!",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Device ID: ${deviceId.take(8)}...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FailureSection(
    reason: PairingState.FailureReason,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = getFailureReasonMessage(reason),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text("Try Again")
        }
    }
}

private fun getQrParseErrorMessage(error: QrParseResult.ErrorCode): String {
    return when (error) {
        QrParseResult.ErrorCode.INVALID_BASE64 -> "Invalid QR code format"
        QrParseResult.ErrorCode.PARSE_ERROR -> "Could not read QR code"
        QrParseResult.ErrorCode.UNSUPPORTED_VERSION -> "QR code version not supported"
        QrParseResult.ErrorCode.MISSING_FIELD -> "QR code is incomplete"
        QrParseResult.ErrorCode.INVALID_SECRET_LENGTH -> "Invalid security data"
        QrParseResult.ErrorCode.INVALID_PORT -> "Invalid connection port"
    }
}

private fun getFailureReasonMessage(reason: PairingState.FailureReason): String {
    return when (reason) {
        PairingState.FailureReason.QR_PARSE_ERROR -> "Invalid QR code"
        PairingState.FailureReason.SIGNALING_FAILED -> "Could not reach host.\nMake sure you're on the same network."
        PairingState.FailureReason.DIRECT_TIMEOUT -> "Direct connection timed out.\nTrying relay..."
        PairingState.FailureReason.NTFY_SUBSCRIBE_FAILED -> "Could not connect to relay.\nPlease check your internet connection."
        PairingState.FailureReason.NTFY_TIMEOUT -> "Relay connection timed out.\nMake sure the host is running."
        PairingState.FailureReason.CONNECTION_FAILED -> "Connection failed.\nPlease try again."
        PairingState.FailureReason.AUTH_FAILED -> "Authentication failed.\nPlease scan a new QR code."
        PairingState.FailureReason.TIMEOUT -> "Connection timed out.\nPlease try again."
    }
}
