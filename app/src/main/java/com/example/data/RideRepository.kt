package com.example.data

import kotlinx.coroutines.flow.Flow

class RideRepository(private val rideDao: RideDao) {
    val allRides: Flow<List<RideRequest>> = rideDao.getAllRides()

    suspend fun insertRide(ride: RideRequest): Long {
        return rideDao.insertRide(ride)
    }

    suspend fun updateRide(ride: RideRequest) {
        rideDao.updateRide(ride)
    }

    suspend fun deleteRide(id: Long) {
        rideDao.deleteRideById(id)
    }

    suspend fun clearHistory() {
        rideDao.clearAllRides()
    }
}
