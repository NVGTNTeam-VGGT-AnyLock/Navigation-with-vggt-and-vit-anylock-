package com.navisense.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [SavedLocation] CRUD operations.
 *
 * All write operations are **suspend** functions for coroutine compatibility.
 * The read operation ([getAll]) returns a **Flow** so the UI layer
 * reactively observes any changes to the underlying table.
 */
@Dao
interface SavedLocationDao {

    /**
     * Insert a new saved location.
     * If a record with the same primary key exists, it is replaced.
     *
     * @param location The [SavedLocation] entity to insert.
     * @return The row ID of the inserted row.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(location: SavedLocation): Long

    /**
     * Update an existing saved location.
     * The entity is matched by its [SavedLocation.id] primary key.
     *
     * @param location The [SavedLocation] with updated fields.
     */
    @Update
    suspend fun update(location: SavedLocation)

    /**
     * Delete a saved location by entity reference.
     *
     * @param location The [SavedLocation] to delete (matched by primary key).
     */
    @Delete
    suspend fun delete(location: SavedLocation)

    /**
     * Delete a saved location by its [id].
     *
     * @param id The primary key of the [SavedLocation] to delete.
     */
    @Query("DELETE FROM saved_locations WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Observe all saved locations ordered by most recently created first.
     *
     * Returns a **Flow** — the downstream collector automatically receives
     * new emissions whenever the underlying table changes.
     */
    @Query("SELECT * FROM saved_locations ORDER BY timestamp DESC")
    fun getAll(): Flow<List<SavedLocation>>

    /**
     * Get a single saved location by its [id].
     * Returns `null` if no record with that ID exists.
     */
    @Query("SELECT * FROM saved_locations WHERE id = :id")
    suspend fun getById(id: Long): SavedLocation?
}
