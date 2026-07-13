package com.example.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.example.data.RideDatabase
import com.example.data.RideRequest
import com.example.data.RideRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.DecimalFormat

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        
        val fare = intent.getDoubleExtra("fare", -1.0)
        val pickupDist = intent.getDoubleExtra("pickup_dist", -1.0)
        val dropDist = intent.getDoubleExtra("drop_dist", -1.0)
        val pickupAddr = intent.getStringExtra("pickup_addr") ?: "Unknown Pickup"
        val dropAddr = intent.getStringExtra("drop_addr") ?: "Unknown Drop"

        if (fare <= 0 || pickupDist < 0 || dropDist < 0) return

        val request = RideRequest(
            totalFare = fare,
            pickupDistance = pickupDist,
            dropDistance = dropDist,
            pickupAddress = pickupAddr,
            dropAddress = dropAddr,
            status = if (action == OverlayService.ACTION_ACCEPT_RIDE) "ACCEPTED" else "REJECTED"
        )

        val database = RideDatabase.getDatabase(context)
        val repository = RideRepository(database.rideDao())

        // Stop the OverlayService to safely close any active floating window
        try {
            val stopIntent = Intent(context, OverlayService::class.java)
            context.stopService(stopIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repository.insertRide(request)
                
                // Dismiss the notification
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(OverlayService.NOTIFICATION_ID_ALERT)
                
                // Switch back to main to show a Toast if necessary
                CoroutineScope(Dispatchers.Main).launch {
                    if (action == OverlayService.ACTION_ACCEPT_RIDE) {
                        val df = DecimalFormat("0.00")
                        Toast.makeText(
                            context.applicationContext,
                            "Ride Accepted! Logged Rs. ${df.format(request.earningsPerKm)}/km",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            context.applicationContext,
                            "Ride Rejected!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    pendingResult.finish()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                pendingResult.finish()
            }
        }
    }
}
