package com.navisense.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for NaviSense local persistence.
 *
 * Holds two tables:
 * - [DeliveryHistory] — immutable log of completed delivery trips.
 * - [SavedLocation]  — mutable set of user-saved favourite points.
 *
 * Uses the singleton pattern to ensure only one database instance exists
 * across the entire application lifetime.
 *
 * To access a DAO from a ViewModel or Repository:
 * ```
 * val db = AppDatabase.getInstance(context)
 * val dao = db.savedLocationDao()
 * dao.getAll().collect { ... }
 * ```
 */
@Database(
    entities = [DeliveryHistory::class, SavedLocation::class],
    version = 2,
    exportSchema = false    // Schema export disabled for MVP
)
abstract class AppDatabase : RoomDatabase() {

    /**
     * Expose the [DeliveryHistoryDao] for delivery history operations.
     * Room generates the implementation at compile time via KSP.
     */
    abstract fun deliveryHistoryDao(): DeliveryHistoryDao

    /**
     * Expose the [SavedLocationDao] for saved-location CRUD operations.
     */
    abstract fun savedLocationDao(): SavedLocationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Returns the singleton [AppDatabase], creating it if necessary.
         *
         * @param context Application context (used only for the initial builder call).
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "navisense_database"          // SQLite file name
                )
                    .fallbackToDestructiveMigration()  // MVP: drop & recreate on version bump
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
