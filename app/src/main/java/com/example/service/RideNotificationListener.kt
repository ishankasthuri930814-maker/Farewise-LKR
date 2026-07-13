package com.example.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.Intent
import android.content.Context
import android.util.Log
import java.util.regex.Pattern

class RideNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = getSharedPreferences("farewise_prefs", Context.MODE_PRIVATE)
        val isServiceEnabled = prefs.getBoolean("service_enabled", false)
        if (!isServiceEnabled) {
            return
        }

        val packageName = sbn.packageName ?: return
        if (packageName == "com.ubercab.driver" || packageName == "com.bhasha.pickme.driver") {
            try {
                val extras = sbn.notification.extras ?: return
                val title = extras.getCharSequence("android.title")?.toString() ?: ""
                val text = extras.getCharSequence("android.text")?.toString() ?: ""
                val subText = extras.getCharSequence("android.subText")?.toString() ?: ""
                
                val combinedText = "$title $text $subText".trim()
                Log.d("RideNotificationListener", "[Notification] Received package=$packageName text='$combinedText'")
                
                // Parse Ride Details
                parseAndTriggerOverlay(combinedText)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun parseAndTriggerOverlay(text: String) {
        // Find Fare/Money
        // Matches e.g., "LKR 450.00", "LKR 450", "Rs. 450", "Rs 450", "රු. 450", "450 LKR", "450 /= "
        val fareRegex = Regex("(?:LKR|Rs\\.|Rs|RS|rs|රු\\.|රු)\\s*([\\d,]+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
        val fareRegexAlt = Regex("([\\d,]+(?:\\.\\d+)?)\\s*(?:LKR|Rs\\.|Rs|RS|rs|රු\\.|රු|/=)", RegexOption.IGNORE_CASE)
        
        var parsedFare = 0.0
        
        val fareMatch1 = fareRegex.find(text)
        if (fareMatch1 != null) {
            val amountStr = fareMatch1.groupValues[1].replace(",", "")
            parsedFare = amountStr.toDoubleOrNull() ?: 0.0
        } else {
            val fareMatch2 = fareRegexAlt.find(text)
            if (fareMatch2 != null) {
                val amountStr = fareMatch2.groupValues[1].replace(",", "")
                parsedFare = amountStr.toDoubleOrNull() ?: 0.0
            }
        }
        
        // Find Distance
        // Matches both number-first (e.g. 1.8 km) and unit-first (e.g. කි.මී. 1.8ක්) formats
        val kmRegex = Regex("([\\d.]+)\\s*(?:km|KM|Km|kilometers|කි\\.\\s*මී\\.|කිමී|කිලෝමීටර්|කිලෝ\\s*මීටර්|කிலோமீட்டர்|கி\\.மீ)", RegexOption.IGNORE_CASE)
        val kmUnitFirstRegex = Regex("(?:කි\\.\\s*මී\\.|කිමී|කිලෝමීටර්|කිලෝ\\s*මීටර්|කிலோமீட்டர்|கி\\.மீ)\\s*([\\d.]+)(?:ක්)?", RegexOption.IGNORE_CASE)
        
        val distancesList = mutableListOf<Double>()
        
        // Match Number-First Kilometers
        val kmMatches = kmRegex.findAll(text).toList()
        for (m in kmMatches) {
            val dist = m.groupValues[1].toDoubleOrNull()
            if (dist != null && dist >= 0.0) {
                distancesList.add(dist)
            }
        }
        
        // Match Unit-First Kilometers
        val kmUnitFirstMatches = kmUnitFirstRegex.findAll(text).toList()
        for (m in kmUnitFirstMatches) {
            val dist = m.groupValues[1].toDoubleOrNull()
            if (dist != null && dist >= 0.0) {
                distancesList.add(dist)
            }
        }
        
        var pickupDist = 0.0
        var dropDist = 0.0
        
        if (distancesList.isNotEmpty()) {
            if (distancesList.size == 1) {
                // If only 1 distance is specified, treat it as the total trip distance
                dropDist = distancesList[0]
            } else {
                // If multiple distances specified (e.g. 1.2 km away, 4.5 km trip)
                pickupDist = distancesList[0]
                dropDist = distancesList[1]
            }
        }
        
        val totalDistance = pickupDist + dropDist
        
        // Distance Guard: Must have a valid distance or a valid parsed fare to be considered a real ride request
        if (totalDistance <= 0.0 && parsedFare <= 0.0) {
            Log.d("RideNotificationListener", "[Notification] Ignoring because totalDistance is 0.0 and no valid fare is parsed.")
            return
        }
        
        if (parsedFare > 0.0) {
            Log.d("RideNotificationListener", "[Notification] Successfully parsed fare=$parsedFare pickup=$pickupDist drop=$dropDist. Launching overlay.")
            
            val intent = Intent(applicationContext, OverlayService::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("total_fare", parsedFare)
                putExtra("pickup_dist", pickupDist)
                putExtra("drop_dist", dropDist)
                putExtra("pickup_addr", "Incoming Hire Notification")
                putExtra("drop_addr", "Auto-Detected Trip")
            }
            
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
