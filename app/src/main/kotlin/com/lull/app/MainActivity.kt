package com.lull.app

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.lull.app.ble.BleLink
import com.lull.app.model.AppModel
import com.lull.app.model.LaunchOptions
import com.lull.app.ui.RootScreen

/**
 * The only screen. Asks for the Bluetooth ("Nearby devices") permissions, and tells the model when Lull comes on
 * screen and leaves it: the BedJet is released whenever Lull leaves the screen, as on iOS.
 */
class MainActivity : ComponentActivity() {
    private lateinit var model: AppModel

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Android doesn't tell the Bluetooth link when access is allowed, so ask for the BedJet again.
        model.appBecameActive()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lull is dark only, so the system bars always get light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        model = (application as LullApplication).obtainModel(launchOptions(intent))
        setContent { RootScreen(model) }
        if (savedInstanceState == null && !BleLink.permissionsGranted(this)) {
            permissionRequest.launch(BleLink.requiredPermissions)
        }
    }

    override fun onStart() {
        super.onStart()
        model.appBecameActive()
    }

    override fun onStop() {
        // Turning the phone round recreates the activity. That isn't leaving the screen, so the BedJet is kept.
        if (!isChangingConfigurations) model.appMovedToBackground()
        super.onStop()
    }

    /**
     * The iOS launch arguments, as Intent extras with the same names:
     * `adb shell am start -n com.lull.app/.MainActivity --ez ephemeral true --ei demoElapsedMinutes 200`.
     */
    private fun launchOptions(intent: Intent): LaunchOptions = LaunchOptions(
        ephemeral = intent.getBooleanExtra("ephemeral", false),
        demoHistory = intent.getBooleanExtra("demoHistory", false),
        demoLabelConnected = intent.getBooleanExtra("demoLabelConnected", false),
        demoElapsedMinutes = intent.getIntExtra("demoElapsedMinutes", 0),
        logConnections = intent.getBooleanExtra("logConnections", false),
        forceDemo = isEmulator(),
    )

    /** The emulator has no Bluetooth, so like the iOS Simulator it always runs the demo. */
    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator") ||
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"
}
