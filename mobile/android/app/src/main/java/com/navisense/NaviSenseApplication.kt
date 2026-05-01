package com.navisense

import android.app.Application
import com.navisense.data.local.AppDatabase

/**
 * Custom [Application] subclass for NaviSense.
 *
 * Lazily initialises the [AppDatabase] singleton so it's available
 * throughout the app without manual dependency injection.
 *
 * **Must be declared in AndroidManifest.xml:**
 * ```xml
 * <application
 *     android:name=".NaviSenseApplication"
 *     ... >
 * ```
 */
class NaviSenseApplication : Application() {

    /**
     * AppDatabase singleton, lazily created on first access.
     * Use [AppDatabase.getInstance] directly from repositories or
     * ViewModels if the Application reference is available.
     */
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        // Future: initialise analytics, crash reporting, DI framework here
    }
}
