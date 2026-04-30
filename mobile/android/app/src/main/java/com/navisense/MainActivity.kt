package com.navisense

import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.navisense.databinding.ActivityMainBinding
import java.util.Locale

/**
 * Single-activity host for the Location Management App.
 *
 * Uses the Navigation Component with a [BottomNavigationView] to switch
 * between five tabs: Map (Home), Routes, Add (+), Analytics, Visual Search.
 *
 * Supports runtime locale switching between English and Ukrainian
 * using [AppCompatDelegate.setApplicationLocales] (API 33+ with compat).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Wire up BottomNavigation with the NavController
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment

        val navController = navHostFragment.navController

        binding.bottomNavigation.setupWithNavController(navController)
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
