package com.example.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.AppLanguage
import com.example.getTxt
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import java.text.DecimalFormat

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun GpsMeterScreen(
    appLanguage: AppLanguage,
    onSaveTrip: (Double, Double, String, String) -> Unit
) {
    val context = LocalContext.current
    val df = remember { DecimalFormat("0.00") }
    val timeFormat = remember { DecimalFormat("00") }

    // SharedPreferences for state persistence of custom rates
    val prefs = remember { context.getSharedPreferences("gps_meter_prefs", Context.MODE_PRIVATE) }

    // Fare rates custom inputs (Default rates in LKR for Tuk/Taxi in LK)
    var rateFirstKmInput by remember { mutableStateOf(prefs.getFloat("rate_first_km", 120f).toString()) }
    var rateSubsequentKmInput by remember { mutableStateOf(prefs.getFloat("rate_subsequent_km", 90f).toString()) }
    var rateWaitingMinuteInput by remember { mutableStateOf(prefs.getFloat("rate_waiting_minute", 15f).toString()) }

    // Core Live trip states
    var isRunning by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }

    var distanceKm by remember { mutableStateOf(0.0) }
    var totalSeconds by remember { mutableStateOf(0L) }
    var waitingSeconds by remember { mutableStateOf(0L) }

    var isManualWaitingEnabled by remember { mutableStateOf(false) }

    // Location & GPS status tracking states
    var gpsStatus by remember { mutableStateOf("searching") } // searching, active, denied, disabled
    var currentLocationState by remember { mutableStateOf<Location?>(null) }
    var lastLocationState by remember { mutableStateOf<Location?>(null) }
    var gpsAccuracy by remember { mutableStateOf(0f) }

    // Parse values safely
    val rateFirstKm = rateFirstKmInput.toDoubleOrNull() ?: 120.0
    val rateSubsequentKm = rateSubsequentKmInput.toDoubleOrNull() ?: 90.0
    val rateWaitingMinute = rateWaitingMinuteInput.toDoubleOrNull() ?: 15.0

    // Save rates whenever input fields are altered correctly
    LaunchedEffect(rateFirstKmInput, rateSubsequentKmInput, rateWaitingMinuteInput) {
        prefs.edit().apply {
            putFloat("rate_first_km", rateFirstKm.toFloat())
            putFloat("rate_subsequent_km", rateSubsequentKm.toFloat())
            putFloat("rate_waiting_minute", rateWaitingMinute.toFloat())
            apply()
        }
    }

    // Live dynamic fare calculation logic
    val currentFare = remember(distanceKm, waitingSeconds, rateFirstKm, rateSubsequentKm, rateWaitingMinute) {
        val waitingMinutes = waitingSeconds / 60.0
        val waitingCost = waitingMinutes * rateWaitingMinute

        if (distanceKm > 0.0 || waitingSeconds > 0) {
            if (distanceKm <= 1.0) {
                rateFirstKm + waitingCost
            } else {
                rateFirstKm + ((distanceKm - 1.0) * rateSubsequentKm) + waitingCost
            }
        } else {
            0.0
        }
    }

    // Precise runtime tick timer
    LaunchedEffect(isRunning, isPaused) {
        if (isRunning && !isPaused) {
            while (true) {
                delay(1000)
                totalSeconds++

                // Automatic Wait detection: if GPS speed is under 1.2 m/s (approx 4.3 km/h) or manual mode is on, accumulate idle/wait
                val isIdle = (currentLocationState?.let { it.hasSpeed() && it.speed < 1.2f } ?: true) || isManualWaitingEnabled
                if (isIdle) {
                    waitingSeconds++
                }
            }
        }
    }

    // GPS Location listener registration scope
    val locationManager = remember { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    val locationListener = remember {
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.accuracy > 50f) return // Skip poor mock/low Accuracy coordinates for safety

                currentLocationState = location
                gpsAccuracy = location.accuracy
                gpsStatus = "active"

                if (isRunning && !isPaused) {
                    val lastLoc = lastLocationState
                    if (lastLoc != null) {
                        val distanceDelta = lastLoc.distanceTo(location) // in meters
                        val speedMps = if (location.hasSpeed()) location.speed else (distanceDelta / 1.0f)

                        // Only accumulate distance if the vehicle speed qualifies as active transit (> 4.3km/h)
                        if (speedMps >= 1.2f) {
                            distanceKm += (distanceDelta / 1000.0)
                        }
                    }
                    lastLocationState = location
                } else if (!isRunning) {
                    lastLocationState = location
                }
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                if (provider == LocationManager.GPS_PROVIDER) {
                    gpsStatus = "disabled"
                }
            }
        }
    }

    // Runtime location permission launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            gpsStatus = "searching"
            try {
                // Request updates every 1 second, with min distance 1 meter
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    1f,
                    locationListener
                )
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    1f,
                    locationListener
                )
            } catch (e: SecurityException) {
                gpsStatus = "denied"
            }
        } else {
            gpsStatus = "denied"
            Toast.makeText(context, getTxt("toast_meter_permission", appLanguage), Toast.LENGTH_LONG).show()
        }
    }

    // Register/unregister location listener during screen states
    DisposableEffect(isRunning, isPaused) {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (hasFine || hasCoarse) {
            gpsStatus = "searching"
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    1f,
                    locationListener
                )
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    1f,
                    locationListener
                )
            } catch (e: SecurityException) {
                gpsStatus = "denied"
            }
        } else {
            gpsStatus = "denied"
        }

        onDispose {
            try {
                locationManager.removeUpdates(locationListener)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Helper functions
    fun formatDuration(totalSecs: Long): String {
        val hrs = totalSecs / 3600
        val mins = (totalSecs % 3600) / 60
        val secs = totalSecs % 60
        return if (hrs > 0) {
            "${timeFormat.format(hrs)}h ${timeFormat.format(mins)}m ${timeFormat.format(secs)}s"
        } else {
            "${timeFormat.format(mins)}m ${timeFormat.format(secs)}s"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Title banner
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = getTxt("meter_title", appLanguage),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    ),
                    color = Color.White
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    val statusDotColor = when (gpsStatus) {
                        "active" -> CyberPrimary
                        "searching" -> ProfitMedium
                        else -> ProfitLow
                    }

                    val statusMsg = when (gpsStatus) {
                        "active" -> getTxt("meter_gps_active", appLanguage)
                        "searching" -> getTxt("meter_gps_search", appLanguage)
                        "disabled" -> "GPS: Service Disabled ❌"
                        else -> getTxt("meter_gps_denied", appLanguage)
                    }

                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(statusDotColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusMsg,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            }

            // Quick Permission Trigger if denied/not configured
            if (gpsStatus == "denied") {
                Button(
                    onClick = {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ProfitLow),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Grant GPS", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Live Meter Fare Display Board (Large display, digital style)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.5.dp, if (isRunning) CyberSecondary else CyberSurfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Trip Category Status Header Badge
                val tripStatusText = when {
                    !isRunning -> getTxt("meter_status_ready", appLanguage)
                    isPaused -> getTxt("meter_status_paused", appLanguage)
                    else -> getTxt("meter_status_running", appLanguage)
                }
                val tripStatusColor = when {
                    !isRunning -> Color.Gray
                    isPaused -> ProfitMedium
                    else -> CyberPrimary
                }

                Box(
                    modifier = Modifier
                        .background(tripStatusColor.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                        .border(1.dp, tripStatusColor, RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = tripStatusText,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = tripStatusColor
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Fare display
                Text(
                    text = getTxt("meter_stats_fare", appLanguage),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
                Text(
                    text = "Rs. ${df.format(currentFare)}",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Black
                    ),
                    color = if (isRunning) CyberSecondary else Color.White,
                    modifier = Modifier.testTag("meter_fare_text")
                )

                Divider(
                    color = CyberSurfaceVariant,
                    modifier = Modifier.padding(vertical = 18.dp),
                    thickness = 0.8.dp
                )

                // Sub-stats grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Distance column
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = CyberPrimary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = getTxt("meter_stats_dist", appLanguage),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                        Text(
                            text = "${df.format(distanceKm)} km",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                            color = Color.White
                        )
                    }

                    // Duration column
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.DateRange, contentDescription = null, tint = CyberSecondary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = getTxt("meter_stats_dur", appLanguage),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                        Text(
                            text = formatDuration(totalSeconds),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                            color = Color.White
                        )
                    }

                    // Waiting column
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = ProfitMedium, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = getTxt("meter_stats_wait", appLanguage),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                        Text(
                            text = formatDuration(waitingSeconds),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Live Action Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!isRunning) {
                // START TRIP BUTTON
                Button(
                    onClick = {
                        isRunning = true
                        isPaused = false
                        distanceKm = 0.0
                        totalSeconds = 0L
                        waitingSeconds = 0L
                        isManualWaitingEnabled = false
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("meter_start_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = CyberPrimary)
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(getTxt("meter_start", appLanguage), fontWeight = FontWeight.Bold)
                }
            } else {
                // END TRIP BUTTON
                Button(
                    onClick = {
                        if (currentFare > 0) {
                            onSaveTrip(currentFare, distanceKm, "GPS Meter Route", "COMPLETED")
                            Toast.makeText(context, getTxt("toast_meter_saved", appLanguage), Toast.LENGTH_SHORT).show()
                        }
                        isRunning = false
                        isPaused = false
                        isManualWaitingEnabled = false
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("meter_stop_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = ProfitLow)
                ) {
                    Icon(imageVector = Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(getTxt("meter_stop", appLanguage), fontWeight = FontWeight.Bold)
                }

                // PAUSE/RESUME TOGGLE BUTTON
                Button(
                    onClick = { isPaused = !isPaused },
                    modifier = Modifier
                        .weight(0.8f)
                        .height(50.dp)
                        .testTag("meter_pause_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isPaused) CyberPrimary else ProfitMedium)
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Refresh,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isPaused) getTxt("meter_resume", appLanguage) else getTxt("meter_pause", appLanguage),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Action tools (Instant Manual Waiting Toggle)
        AnimatedVisibility(visible = isRunning && !isPaused) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CyberSurfaceVariant.copy(alpha = 0.4f))
                    .border(1.0.dp, CyberSurfaceVariant, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = ProfitMedium, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (appLanguage == AppLanguage.SINHALA) "හස්තීය පොරොත්තු ප්‍රකාරය" else "Manual Waiting Assist",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = if (appLanguage == AppLanguage.SINHALA) "වාහනය නිශ්චලව තිබියදී තට්ටු කරන්න" else "Activate when forced to stand still",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }

                Switch(
                    checked = isManualWaitingEnabled,
                    onCheckedChange = { isManualWaitingEnabled = it },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = ProfitMedium,
                        checkedThumbColor = CyberBackground
                    )
                )
            }
        }

        // Fare Rate Configuration Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, CyberSurfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = getTxt("meter_rates_title", appLanguage),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = CyberSecondary
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Rate Inputs
                OutlinedTextField(
                    value = rateFirstKmInput,
                    onValueChange = { rateFirstKmInput = it },
                    label = { Text(getTxt("meter_rate_first", appLanguage)) },
                    placeholder = { Text("e.g. 150") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, tint = CyberPrimary) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("config_first_km"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = CyberPrimary,
                        unfocusedBorderColor = CyberSurfaceVariant,
                        focusedLabelColor = CyberPrimary,
                        unfocusedLabelColor = Color.Gray,
                        focusedPlaceholderColor = Color.LightGray,
                        unfocusedPlaceholderColor = Color.Gray
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = rateSubsequentKmInput,
                    onValueChange = { rateSubsequentKmInput = it },
                    label = { Text(getTxt("meter_rate_sub", appLanguage)) },
                    placeholder = { Text("e.g. 100") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = CyberSecondary) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("config_sub_km"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = CyberSecondary,
                        unfocusedBorderColor = CyberSurfaceVariant,
                        focusedLabelColor = CyberSecondary,
                        unfocusedLabelColor = Color.Gray,
                        focusedPlaceholderColor = Color.LightGray,
                        unfocusedPlaceholderColor = Color.Gray
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = rateWaitingMinuteInput,
                    onValueChange = { rateWaitingMinuteInput = it },
                    label = { Text(getTxt("meter_rate_wait", appLanguage)) },
                    placeholder = { Text("e.g. 15") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = ProfitMedium) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("config_wait"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = ProfitMedium,
                        unfocusedBorderColor = CyberSurfaceVariant,
                        focusedLabelColor = ProfitMedium,
                        unfocusedLabelColor = Color.Gray,
                        focusedPlaceholderColor = Color.LightGray,
                        unfocusedPlaceholderColor = Color.Gray
                    )
                )
            }
        }
    }
}
