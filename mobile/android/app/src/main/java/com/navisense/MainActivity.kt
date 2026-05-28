package com.navisense

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.navigation.NavigationView
import com.navisense.databinding.ActivityMainBinding
import java.util.Locale

/**
 * Single-activity host for the NaviSense app.
 *
 * ## Navigation Architecture (Hybrid)
 *
 * - **BottomNavigationView** (2 tabs): Transport, Pedestrian
 *   — the primary scanning modes accessible from the bottom bar.
 *
 * - **DrawerLayout** → **NavigationView**: Legacy management screens
 *   (Map, Routes, Add, Analytics, Visual Search) plus mode switching,
 *   accessible via the hamburger (☰) button in the top-left corner.
 *
 * Supports runtime locale switching between English and Ukrainian
 * using [AppCompatDelegate.setApplicationLocales] (API 33+ with compat).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var drawerLayout: DrawerLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        drawerLayout = binding.drawerLayout

        // ── NavHostFragment ──────────────────────────────────────────
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment

        navController = navHostFragment.navController

        // ── Bottom Navigation (Transport / Pedestrian) ───────────────
        binding.bottomNavigation.setupWithNavController(navController)

        // ── Navigation Drawer (legacy tabs + management) ─────────────
        val navView: NavigationView = binding.navView
        NavigationUI.setupWithNavController(navView, navController)

        // ── Hamburger Toggle ─────────────────────────────────────────
        binding.btnDrawerToggle.setOnClickListener {
            if (drawerLayout.isDrawerOpen(navView)) {
                drawerLayout.closeDrawer(navView)
            } else {
                drawerLayout.openDrawer(navView)
            }
        }

        // ── Close drawer on navigation (auto‑close when item selected) ─
        // NOTE: Bottom‑nav selection syncing is handled internally by
        // setupWithNavController() — do NOT manually set selectedItemId
        // here, as that would trigger a re‑entrant navigate() call and crash.
        navController.addOnDestinationChangedListener { _, _: NavDestination, _ ->
            if (drawerLayout.isDrawerOpen(navView)) {
                drawerLayout.closeDrawer(navView)
            }
        }

        // ── Sync initial bottom nav selection ────────────────────────
        val startDestId = navController.graph.startDestinationId
        if (startDestId == R.id.transportFragment || startDestId == R.id.pedestrianFragment) {
            binding.bottomNavigation.selectedItemId = startDestId
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
                Locale.getDefault().language
            } else {
                locales[0]?.language ?: "en"
            }
        }
    }
}
