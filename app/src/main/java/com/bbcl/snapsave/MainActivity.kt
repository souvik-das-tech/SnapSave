package com.bbcl.snapsave

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.bbcl.snapsave.download.Downloads
import com.bbcl.snapsave.ui.HomeScreen
import com.bbcl.snapsave.ui.SnapSaveTheme

class MainActivity : ComponentActivity() {
    /** Link shared from another app, waiting for the format sheet to open. */
    private val shared = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light status bar icons: they sit on the pink/purple header in both themes.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) takeShared(intent)
        setContent { SnapSaveTheme { HomeScreen(shared.value, onSharedHandled = { shared.value = null }) } }

        // Progress notifications on Android 13+, and the public Downloads folder on Android 8/9.
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1) else askBackground()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // After the notification prompt, so the two system dialogs don't stack.
        askBackground()
    }

    /**
     * Once, on first open: battery optimisation (very aggressive on Realme, Oppo, Vivo,
     * Xiaomi) pauses downloads when the screen is off. Settings keeps an "Allow" row for later.
     */
    private fun askBackground() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getBoolean("askedBackground", false)) return
        prefs.edit().putBoolean("askedBackground", true).apply()
        if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    override fun onStart() {
        super.onStart()
        // A newer yt-dlp shows up as a prompt on the home screen.
        Downloads.checkForUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShared(intent)
    }

    private fun takeShared(intent: Intent?) {
        intent?.getStringExtra(EXTRA_URL)?.let { shared.value = it }
        intent?.removeExtra(EXTRA_URL)
    }

    companion object {
        const val EXTRA_URL = "com.bbcl.snapsave.URL"
    }
}
