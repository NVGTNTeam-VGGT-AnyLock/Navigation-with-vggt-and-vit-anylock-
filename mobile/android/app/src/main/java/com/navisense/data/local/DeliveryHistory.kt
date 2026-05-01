package com.navisense.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Entity representing a completed delivery route.
 *
 * Logs each delivery trip the courier finishes, recording the start/end
 * locations, how many times GPS was lost during the trip, and the estimated
 * time saved by using NaviSense visual positioning vs. relying on GPS alone.
 *
 * @property id              Auto-generated primary key.
 * @property address         Delivery destination address string.
 * @property startPointLat   Start latitude (WGS‑84).
 * @property startPointLng   Start longitude (WGS‑84).
 * @property endPointLat     End/delivery latitude (WGS‑84).
 * @property endPointLng     End/delivery longitude (WGS‑84).
 * @property gpsDropsCount   Number of times GPS signal was lost/dropped during this trip.
 * @property timeSavedSeconds Estimated time saved (in seconds) vs. GPS-only navigation.
 * @property timestamp       Epoch millis when this record was created (default: now).
 */
@Entity(tableName = "delivery_history")
data class DeliveryHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val address: String,
    val startPointLat: Double,
    val startPointLng: Double,
    val endPointLat: Double,
    val endPointLng: Double,
    val gpsDropsCount: Int,
    val timeSavedSeconds: Long,
    val timestamp: Long = System.currentTimeMillis()
)
