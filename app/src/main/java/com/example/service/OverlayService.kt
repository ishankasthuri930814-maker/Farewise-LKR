package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.example.MainActivity
import com.example.data.RideDatabase
import com.example.data.RideRequest
import com.example.data.RideRepository
import com.example.ui.theme.CyberBackground
import com.example.ui.theme.CyberPrimary
import com.example.ui.theme.CyberSecondary
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.ProfitHigh
import com.example.ui.theme.ProfitLow
import com.example.ui.theme.ProfitMedium
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.DecimalFormat

class OverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private lateinit var repository: RideRepository

    private val decimalFormat = DecimalFormat("0.00")

    private var autoDismissJob: kotlinx.coroutines.Job? = null
    private var lastLoggedFare: Double = -1.0
    private var lastLoggedDistance: Double = -1.0
    private var lastLogTime: Long = 0

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                removeFloatingOverlay()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val database = RideDatabase.getDatabase(this)
        repository = RideRepository(database.rideDao())
        
        createNotificationChannel()

        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            registerReceiver(screenOffReceiver, filter)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Start foreground service *immediately* inside onCreate to prevent ForegroundServiceDidNotStartInTimeException
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID_FOREGROUND,
                    createOngoingNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID_FOREGROUND, createOngoingNotification())
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                stopSelf()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (lifecycleRegistry.currentState < Lifecycle.State.STARTED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            }
            if (lifecycleRegistry.currentState < Lifecycle.State.RESUMED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        val totalFare = intent?.getDoubleExtra("total_fare", -1.0) ?: -1.0
        val pickupDistance = intent?.getDoubleExtra("pickup_dist", -1.0) ?: -1.0
        val dropDistance = intent?.getDoubleExtra("drop_dist", -1.0) ?: -1.0
        val pickupAddress = intent?.getStringExtra("pickup_addr") ?: "Unknown Pickup"
        val dropAddress = intent?.getStringExtra("drop_addr") ?: "Unknown Drop"
        
        if (totalFare > 0 && pickupDistance >= 0 && dropDistance >= 0) {
            val totalDist = pickupDistance + dropDistance
            val now = System.currentTimeMillis()
            
            // Prevent duplicate logs of the exact same ride request within 8 seconds
            val isDuplicate = totalFare == lastLoggedFare && 
                              totalDist == lastLoggedDistance && 
                              (now - lastLogTime) < 8000
                              
            val request = RideRequest(
                totalFare = totalFare,
                pickupDistance = pickupDistance,
                dropDistance = dropDistance,
                pickupAddress = pickupAddress,
                dropAddress = dropAddress,
                status = "ACCEPTED" // All automatically logged rides are marked as saved (ACCEPTED)
            )
            
            if (!isDuplicate) {
                lastLoggedFare = totalFare
                lastLoggedDistance = totalDist
                lastLogTime = now
                
                // Save immediately into database
                serviceScope.launch {
                    repository.insertRide(request)
                }
            }
            
            // Try drawing overlay on top of screen if allowed
            if (Settings.canDrawOverlays(this)) {
                showFloatingOverlay(request)
            }
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: Exception) {
            // Might not be registered
        }
        try {
            removeFloatingOverlay()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        try {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        try {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        try {
            serviceJob.cancel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        try {
            store.clear()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        super.onDestroy()
    }

    private fun createOngoingNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID_FOREGROUND)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("FareWise Driver Companion")
            .setContentText("Background Real-time Ride Monitor is active")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Foreground running channel
            val fChannel = NotificationChannel(
                CHANNEL_ID_FOREGROUND,
                "FareWise Background Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(fChannel)

            // High Priority heads-up ride alert channel
            val hChannel = NotificationChannel(
                CHANNEL_ID_ALERTS,
                "Ride Request Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows real-time Earnings/KM calculations for new ride requests."
                enableLights(true)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(hChannel)
        }
    }

    private fun showHeadsUpNotification(request: RideRequest) {
        val totalDist = request.pickupDistance + request.dropDistance
        val rate = request.earningsPerKm
        val rateFormatted = "Rs. ${decimalFormat.format(rate)}/km"
        
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("show_dialog", true)
            putExtra("fare", request.totalFare)
            putExtra("pickup_dist", request.pickupDistance)
            putExtra("drop_dist", request.dropDistance)
            putExtra("pickup_addr", request.pickupAddress)
            putExtra("drop_addr", request.dropAddress)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, request.hashCode(), activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Broadcast receivers for actions
        val acceptIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = ACTION_ACCEPT_RIDE
            putExtra("fare", request.totalFare)
            putExtra("pickup_dist", request.pickupDistance)
            putExtra("drop_dist", request.dropDistance)
            putExtra("pickup_addr", request.pickupAddress)
            putExtra("drop_addr", request.dropAddress)
        }
        val acceptPending = PendingIntent.getBroadcast(
            this, request.hashCode() + 1, acceptIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val rejectIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = ACTION_REJECT_RIDE
            putExtra("fare", request.totalFare)
            putExtra("pickup_dist", request.pickupDistance)
            putExtra("drop_dist", request.dropDistance)
            putExtra("pickup_addr", request.pickupAddress)
            putExtra("drop_addr", request.dropAddress)
        }
        val rejectPending = PendingIntent.getBroadcast(
            this, request.hashCode() + 2, rejectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID_ALERTS)
        } else {
            Notification.Builder(this)
        }

        val profitSummary = when {
            rate >= 100 -> "✨ High Profit: $rateFormatted"
            rate >= 60 -> "✅ Moderate: $rateFormatted"
            else -> "⚠️ Low Profit: $rateFormatted"
        }

        val textDesc = "Fare: LKR ${request.totalFare} | Dist: ${decimalFormat.format(totalDist)} km"

        val notification = builder
            .setContentTitle("New Ride Offered - $profitSummary")
            .setContentText(textDesc)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setPriority(Notification.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_edit, "ACCEPT (Earns $rateFormatted)", acceptPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "REJECT", rejectPending)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_ALERT, notification)
    }

    private fun showFloatingOverlay(request: RideRequest) {
        removeFloatingOverlay()
        
        autoDismissJob?.cancel()

        val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 70 // Offset slightly from status bar
        }

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@OverlayService))
            // Support Compose lifecycle binding to Service
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
        }

        composeView.setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = CyberPrimary,
                    background = CyberBackground,
                    surface = CyberSurface
                )
            ) {
                OverlayCard(
                    request = request,
                    onClose = {
                        removeFloatingOverlay()
                    }
                )
            }
        }

        try {
            windowManager?.addView(composeView, params)
            floatView = composeView
            
            // Auto dismiss the overlay after 12 seconds to not block PickMe/Uber UI
            autoDismissJob = serviceScope.launch {
                kotlinx.coroutines.delay(12000)
                removeFloatingOverlay()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to inflate calculation overlay", Toast.LENGTH_SHORT).onResult()
        }
    }

    private fun Toast.onResult() {}

    private fun removeFloatingOverlay() {
        autoDismissJob?.cancel()
        autoDismissJob = null
        
        floatView?.let { view ->
            try {
                if (view.isAttachedToWindow) {
                    windowManager?.removeView(view)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                if (view is ComposeView) {
                    view.disposeComposition()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            floatView = null
        }
    }

    companion object {
        const val CHANNEL_ID_FOREGROUND = "farewise_bg_monitor"
        const val CHANNEL_ID_ALERTS = "farewise_ride_alerts"
        const val NOTIFICATION_ID_FOREGROUND = 8881
        const val NOTIFICATION_ID_ALERT = 8882

        const val ACTION_ACCEPT_RIDE = "com.example.action.ACCEPT_RIDE"
        const val ACTION_REJECT_RIDE = "com.example.action.REJECT_RIDE"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayCard(
    request: RideRequest,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val isSinhala = remember {
        val prefs = context.getSharedPreferences("farewise_prefs", Context.MODE_PRIVATE)
        prefs.getString("lang", "en") == "si"
    }

    val totalDist = request.pickupDistance + request.dropDistance
    val epk = request.earningsPerKm
    val df = remember { DecimalFormat("0.00") }

    val rateColor = when {
        epk >= 100.0 -> ProfitHigh
        epk >= 60.0 -> ProfitMedium
        else -> ProfitLow
    }

    val rateLabel = when {
        epk >= 100.0 -> if (isSinhala) "ඉහළ ලාභදායී 🔥" else "HIGH PROFIT 🔥"
        epk >= 60.0 -> if (isSinhala) "සාමාන්‍යයි ✅" else "MODERATE OK ✅"
        else -> if (isSinhala) "අලාභදායකයි ⚠️" else "UNPROFITABLE ⚠️"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .border(1.dp, rateColor.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CyberSurface.copy(alpha = 0.58f)),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Ride",
                        tint = CyberSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isSinhala) "නව හයර් පණිවිඩයකි" else "NEW RIDE ALERT",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 0.5.sp
                        ),
                        color = Color.White
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Profit Badge
                    Box(
                        modifier = Modifier
                            .background(rateColor.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = rateLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 9.sp
                            ),
                            color = rateColor
                        )
                    }

                    // Compact Close Button
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Main Earnings Banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = if (isSinhala) "කිලෝමීටරයකට ඉපැයීම" else "EARNINGS PER KM",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = Color.Gray
                    )
                    Text(
                        text = "Rs. ${df.format(epk)}/km",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Black
                        ),
                        color = rateColor
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (isSinhala) "මුළු ගාස්තුව" else "TOTAL FARE",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = Color.Gray
                    )
                    Text(
                        text = "Rs. ${request.totalFare}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Total Distance Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = CyberSecondary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isSinhala) "ගමන් විස්තරය" else "Fare Calculation Info",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Color.Gray
                    )
                }
                Text(
                    text = if (isSinhala) "මුළු දුර: ${df.format(totalDist)} km" else "Total Dist: ${df.format(totalDist)} km",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 11.sp),
                    color = CyberSecondary
                )
            }
        }
    }
}
