package com.phonerobot.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.phonerobot.app.ui.MainScreen
import com.phonerobot.app.ui.PhoneRobotTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val viewModel: PhoneRobotViewModel by lazy {
        ViewModelProvider(this)[PhoneRobotViewModel::class.java]
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.i(TAG, "RECORD_AUDIO permission granted")
            viewModel.onAudioPermissionGranted()
        } else {
            Log.w(TAG, "RECORD_AUDIO permission denied")
            viewModel.onAudioPermissionDenied()
        }
    }

    private val requestBlePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.i(TAG, "BLE permissions granted")
            viewModel.startBleScan()
        } else {
            val denied = permissions.filterValues { !it }.keys
            Log.e(TAG, "BLE permissions DENIED: $denied")
            viewModel.postSystemMessage(
                "BLE scan needs: ${denied.joinToString(", ")}. Grant in Settings > Apps > PhoneRobot > Permissions."
            )
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open app settings", e)
            }
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.i(TAG, "Bluetooth enabled, starting scan")
            prepareBleScan()
        } else {
            viewModel.postSystemMessage("Bluetooth must be enabled to scan for devices.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: initializing PhoneRobot app")

        setContent {
            PhoneRobotTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val state by viewModel.uiState.collectAsState()
                    val snackbarHostState = remember { SnackbarHostState() }
                    val context = LocalContext.current

                    LaunchedEffect(Unit) {
                        viewModel.effects.collect { effect ->
                            val actionLabel = effect.action?.let { context.getString(it.labelRes) }
                            val result = snackbarHostState.showSnackbar(
                                message = effect.text,
                                actionLabel = actionLabel,
                                duration = SnackbarDuration.Long
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                viewModel.onSnackAction(effect.action)
                            }
                        }
                    }

                    MainScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onDestinationChanged = viewModel::setDestination,
                        onInputChanged = viewModel::setInput,
                        onSendClicked = viewModel::sendMessage,
                        onVoiceClicked = ::onVoiceClicked,
                        onRobotMicClicked = ::onRobotMicClicked,
                        onConnectUsb = viewModel::connectUsbDevice,
                        onScanBle = ::prepareBleScan,
                        onConnectBle = viewModel::connectBle,
                        onDisconnectBle = viewModel::disconnectBle,
                    )
                }
            }
        }
    }

    // ── Voice: permission gate before recording starts ───────────

    private fun onVoiceClicked() {
        if (viewModel.uiState.value.isRecording) {
            viewModel.stopRecordingAndSend()
            return
        }
        if (hasAudioPermission()) {
            viewModel.startRecording()
        } else {
            viewModel.onAudioPermissionRequested(PhoneRobotViewModel.MicRequest.ChatRecording)
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Robot Mode mic (ROBOT tab): toggles continuous listening in place. */
    private fun onRobotMicClicked() {
        if (viewModel.uiState.value.robotModeRunning) {
            viewModel.stopRobotMode()
            return
        }
        if (hasAudioPermission()) {
            viewModel.startRobotMode()
        } else {
            viewModel.onAudioPermissionRequested(PhoneRobotViewModel.MicRequest.RobotMode)
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ── BLE scan: BT / location / permission gates ───────────────

    private fun prepareBleScan() {
        if (!isBluetoothEnabled()) {
            Log.w(TAG, "Bluetooth not enabled, requesting...")
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        if (!isLocationEnabled()) {
            Log.w(TAG, "Location services disabled — BLE scan requires location on Android")
            viewModel.postSystemMessage("Please enable Location services (required for BLE scanning)")
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open location settings", e)
            }
            return
        }

        val missingPermissions = requiredMissingBlePermissions()
        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "Missing permissions: $missingPermissions, requesting...")
            requestBlePermissionLauncher.launch(missingPermissions.toTypedArray())
            return
        }

        viewModel.startBleScan()
    }

    private fun isBluetoothEnabled(): Boolean =
        (getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
            ?.adapter?.isEnabled == true

    @Suppress("DEPRECATION")
    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun requiredMissingBlePermissions(): List<String> {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) missing.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) missing.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            @Suppress("DEPRECATION")
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                != PackageManager.PERMISSION_GRANTED
            ) missing.add(Manifest.permission.BLUETOOTH)
            @Suppress("DEPRECATION")
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
                != PackageManager.PERMISSION_GRANTED
            ) missing.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return missing
    }

    // ── USB device attached while running ────────────────────────

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            @Suppress("DEPRECATION")
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    android.hardware.usb.UsbManager.EXTRA_DEVICE,
                    android.hardware.usb.UsbDevice::class.java
                )
            } else {
                intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
            }
            if (device != null) {
                viewModel.onUsbDeviceAttached(device)
            }
        }
    }
}
