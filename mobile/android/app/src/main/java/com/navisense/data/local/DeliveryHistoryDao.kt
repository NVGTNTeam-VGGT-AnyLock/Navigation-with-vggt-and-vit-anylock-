package com.navisense.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [DeliveryHistory] CRUD operations.
 *
 * All write operations are **suspend** functions for coroutine compatibility.
 * Read operations return **Flow<T>** so the UI layer can reactively observe
 * changes — Room runs these queries on a background thread automatically.
 */
@Dao
interface DeliveryHistoryDao {

    /**
     * Insert a new delivery history record.
     * If a record with the same primary key exists, it is replaced.
     *
     * @param delivery The [DeliveryHistory] entity to insert.
     * @return The row ID of the inserted row.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(delivery: DeliveryHistory): Long

    /**
     * Observe all delivery history records, ordered by most recent first.
     *
     * Returns a **Flow** — the downstream collector (e.g., ViewModel or
     * Repository) will automatically receive new emissions whenever the
     * underlying table changes.
     */
    @Query("SELECT * FROM delivery_history ORDER BY timestamp DESC")
    fun getAllDeliveries(): Flow<List<DeliveryHistory>>

    /**
     * Get a single delivery record by its [id].
     * Returns `null` if no record with that ID exists.
     */
    @Query("SELECT * FROM delivery_history WHERE id = :id")
    suspend fun getDeliveryById(id: Long): DeliveryHistory?

    /**
     * Delete all delivery history records (useful for debug / testing).
     */
    @Query("DELETE FROM delivery_history")
    suspend fun deleteAll()

    /**
     * Observe only the most recent delivery record.
     * Useful for displaying a "last trip summary" on the Analytics screen.
     */
    @Query("SELECT * FROM delivery_history ORDER BY timestamp DESC LIMIT 1")
    fun getLatestDelivery(): Flow<DeliveryHistory?>
}
