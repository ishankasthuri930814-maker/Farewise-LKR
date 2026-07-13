package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ride_requests")
data class RideRequest(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val totalFare: Double,
    val pickupDistance: Double,
    val dropDistance: Double,
    val pickupAddress: String,
    val dropAddress: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "PENDING" // PENDING, ACCEPTED, REJECTED, MISSED
) {
    // Earnings per Kilometer = Total Fare / (Pickup Distance + Drop Distance)
    val earningsPerKm: Double
        get() = if (pickupDistance + dropDistance > 0) {
            totalFare / (pickupDistance + dropDistance)
        } else {
            0.0
        }
}
