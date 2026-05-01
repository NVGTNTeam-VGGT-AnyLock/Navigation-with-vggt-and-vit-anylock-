package com.navisense.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Entity representing a user-saved favourite point (Saved Location).
 *
 * Couriers can tap on the map or search for a place and save it here with
 * custom metadata. Unlike [DeliveryHistory] (which is an immutable log),
 * these records are fully mutable via CRUD operations.
 *
 * @property id          Auto-generated primary key.
 * @property name        Human-readable name for this saved point (e.g., "Warehouse 4B").
 * @property description Free-text description or notes about the location.
 * @property category    Category string matching [com.navisense.model.AppLocationCategory] keys.
 * @property latitude    WGS‑84 latitude.
 * @property longitude   WGS‑84 longitude.
 * @property timestamp   Epoch millis when this record was created (default: now).
 */
@Entity(tableName = "saved_locations")
data class SavedLocation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long = System.currentTimeMillis()
)
