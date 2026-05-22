package com.navisense

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import kotlinx.coroutines.launch
import com.navisense.core.DashcamBackgroundService
import com.navisense.databinding.ActivityMainBinding
import com.navisense.model.AppLocation
import com.navisense.model.AppLocationCategory
import com.navisense.model.NavMode
import com.navisense.ui.MainViewModel
import java.util.Locale

/**
 * Single-activity host for the Location Management App.
 *
 * Uses the Navigation Component with a [BottomNavigationView] to switch
 * between five tabs: Map (Home), Routes, Add (+), Analytics, Visual Search.
 *
 * Supports runtime locale switching between English and Ukrainian
 * using [AppCompatDelegate.setApplicationLocales] (API 33+ with compat).
 *
 * ## Dashcam Integration
 * Listens for [DashcamBackgroundService.ACTION_DASHCAM_LOCATION_UPDATE]
 * broadcasts and forwards them to [MainViewModel.publishDashcamLocation]
 * so the MapFragment can render the live tracking marker.
 *
 * Starts/stops [DashcamBackgroundService] when the user toggles
 * [NavMode.DASHCAM] / [NavMode.SCANNER].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Shared [MainViewModel] — same instance used by MapFragment. */
    private val viewModel: MainViewModel by viewModels()

    /** Tracks whether the broadcast receiver is registered (avoid double‑register). */
    private var isDashcamReceiverRegistered = false

    // ── Dashcam permissions launcher (CAMERA + POST_NOTIFICATIONS) ──

    private val dashcamPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else {
            true // Pre-33, no runtime notification permission needed
        }

        if (cameraGranted && notificationsGranted) {
            // All required permissions granted — start the service
            DashcamBackgroundService.start(this)
        } else {
            // Permission denied — revert nav mode back to SCANNER
            viewModel.setNavMode(NavMode.SCANNER)
            Toast.makeText(
                this,
                if (!cameraGranted) R.string.permission_camera_required
                else R.string.dashcam_permission_required,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── Dashcam Location Broadcast Receiver ────────────────────────────

    /**
     * Receives [DashcamBackgroundService.ACTION_DASHCAM_LOCATION_UPDATE]
     * intents and pushes the geo‑coordinates into [MainViewModel.publishDashcamLocation].
     */
    private val dashcamLocationReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val lat = intent.getDoubleExtra(
                DashcamBackgroundService.EXTRA_LATITUDE, 0.0
            )
            val lon = intent.getDoubleExtra(
                DashcamBackgroundService.EXTRA_LONGITUDE, 0.0
            )
            val confidence = intent.getFloatExtra(
                DashcamBackgroundService.EXTRA_CONFIDENCE, 0f
            )

            viewModel.publishDashcamLocation(
                AppLocation(
                    id = 0,
                    title = getString(R.string.dashcam_marker_title),
                    description = "Dashcam live visual fix. Conf=" +
                            "%.2f".format(confidence.toDouble()) + ".",
                    latitude = lat,
                    longitude = lon,
                    category = AppLocationCategory.MONUMENT.key,
                    imageUri = "",
                    isVisited = false,
                    isFavorite = false
                )
            )
        }
    }

    // ── Android Lifecycle ─────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Wire up BottomNavigation with the NavController
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment

        val navController = navHostFragment.navController

        binding.bottomNavigation.setupWithNavController(navController)

        // ── Observe navMode → start/stop DashcamBackgroundService ─────
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.navMode.collect { mode ->
                    when (mode) {
                        NavMode.DASHCAM -> requestDashcamPermissionAndStart()
                        NavMode.SCANNER -> DashcamBackgroundService.stop(this@MainActivity)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isDashcamReceiverRegistered) {
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                dashcamLocationReceiver,
                android.content.IntentFilter(DashcamBackgroundService.ACTION_DASHCAM_LOCATION_UPDATE),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isDashcamReceiverRegistered = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (isDashcamReceiverRegistered) {
            unregisterReceiver(dashcamLocationReceiver)
            isDashcamReceiverRegistered = false
        }
    }

    // ── Dashcam Permission & Start Helpers ────────────────────────────

    /**
     * Checks for [Manifest.permission.CAMERA] and
     * [Manifest.permission.POST_NOTIFICATIONS] (API 33+) and starts
     * [DashcamBackgroundService] only once ALL required permissions
     * are granted.
     *
     * On Android 14 (API 34), starting a foreground service with
     * [android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA]
     * requires the `CAMERA` runtime permission — without it the system
     * throws [android.app.ForegroundServiceStartNotAllowedException].
     *
     * If the user denies any permission, the nav mode is reverted to
     * [NavMode.SCANNER] and a Toast explains the requirement.
     */
    private fun requestDashcamPermissionAndStart() {
        val cameraGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (cameraGranted && notificationsGranted) {
            // All permissions already granted — start immediately
            DashcamBackgroundService.start(this)
        } else {
            // Request all missing permissions together in one dialog
            val permissionsToRequest = mutableListOf<String>()
            if (!cameraGranted) permissionsToRequest.add(Manifest.permission.CAMERA)
            if (!notificationsGranted) permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            dashcamPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    companion object {
        /**
         * Switch the app's locale at runtime.
         * Uses [AppCompatDelegate.setApplicationLocales] for API 33+
         * with automatic backward compatibility via [LocaleListCompat].
         *
         * @param languageCode ISO 639-1 language code (e.g., "en", "uk").
         */
        @JvmStatic
        fun switchLocale(languageCode: String) {
            val localeList = LocaleListCompat.forLanguageTags(languageCode)
            AppCompatDelegate.setApplicationLocales(localeList)
        }

        /**
         * Get the current app locale language code.
         */
        @JvmStatic
        fun getCurrentLocaleCode(): String {
            val locales = AppCompatDelegate.getApplicationLocales()
            return if (locales.isEmpty) {
                // Default to system locale
                Locale.getDefault().language
            } else {
                locales[0]?.language ?: "en"
            }
        }
    }
}
