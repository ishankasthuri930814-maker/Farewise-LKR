package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RideDao {
    @Query("SELECT * FROM ride_requests ORDER BY timestamp DESC")
    fun getAllRides(): Flow<List<RideRequest>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRide(ride: RideRequest): Long

    @Update
    suspend fun updateRide(ride: RideRequest)

    @Query("DELETE FROM ride_requests WHERE id = :id")
    suspend fun deleteRideById(id: Long)

    @Query("DELETE FROM ride_requests")
    suspend fun clearAllRides()
}
