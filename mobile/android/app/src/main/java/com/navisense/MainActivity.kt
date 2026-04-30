package com.navisense

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.navisense.databinding.ActivityMainBinding

/**
 * Single-activity host for the Location Management App.
 *
 * Uses the Navigation Component with a [BottomNavigationView] to switch
 * between five tabs: Map (Home), Routes, Add (+), Analytics, Visual Search.
 *
 * All screen logic lives in dedicated Fragments under the `ui.` package.
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
}
