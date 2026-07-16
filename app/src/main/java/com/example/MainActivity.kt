package com.example

import android.Manifest
import android.util.Log
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import android.app.Activity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.data.RideDatabase
import com.example.data.RideRequest
import com.example.data.RideRepository
import com.example.service.OverlayService
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DecimalFormat

// VM to manage core state, databases, simulation triggers
class MainViewModel(private val repository: RideRepository) : ViewModel() {
    
    val ridesState: StateFlow<List<RideRequest>> = repository.allRides
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _isServiceActive = MutableStateFlow(false)
    val isServiceActive: StateFlow<Boolean> = _isServiceActive

    fun setServiceActive(active: Boolean) {
        _isServiceActive.value = active
    }

    fun addManualRide(ride: RideRequest) {
        viewModelScope.launch {
            repository.insertRide(ride)
        }
    }

    fun deleteRide(id: Long) {
        viewModelScope.launch {
            repository.deleteRide(id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }
}

// Factory for VM
class MainViewModelFactory(private val repository: RideRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class MainActivity : ComponentActivity() {

    companion object {
        var isAppInForeground = false
    }

    override fun onResume() {
        super.onResume()
        isAppInForeground = true
    }

    override fun onPause() {
        super.onPause()
        isAppInForeground = false
    }

    private lateinit var viewModel: MainViewModel
    private var mInterstitialAd: com.google.android.gms.ads.interstitial.InterstitialAd? = null

    fun loadInterstitialAd() {
        val prefs = getSharedPreferences("farewise_prefs", Context.MODE_PRIVATE)
        val adsEnabled = prefs.getBoolean("ads_enabled", true)
        if (!adsEnabled) {
            mInterstitialAd = null
            return
        }

        val adRequest = com.google.android.gms.ads.AdRequest.Builder().build()
        val forceTestAds = prefs.getBoolean("admob_force_test_ads", false)
        val isEmulatorDevice = isEmulator(this)

        val adUnitId = if (forceTestAds || isEmulatorDevice) {
            "ca-app-pub-3940256099942544/1033173712" // Test Interstitial ID
        } else {
            prefs.getString("admob_interstitial_id", "ca-app-pub-7472113156561687/1632907177") ?: "ca-app-pub-7472113156561687/1632907177"
        }

        Log.d("AdMob", "Loading interstitial with ID: $adUnitId (ForceTest: $forceTestAds, Emulator: $isEmulatorDevice)")

        com.google.android.gms.ads.interstitial.InterstitialAd.load(
            this,
            adUnitId,
            adRequest,
            object : com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback() {
                override fun onAdFailedToLoad(adError: com.google.android.gms.ads.LoadAdError) {
                    Log.e("AdMob", "Interstitial failed to load: ${adError.message} (Code: ${adError.code})")
                    mInterstitialAd = null
                }

                override fun onAdLoaded(interstitialAd: com.google.android.gms.ads.interstitial.InterstitialAd) {
                    mInterstitialAd = interstitialAd
                    Log.d("AdMob", "Interstitial loaded successfully with ID: $adUnitId")
                }
            }
        )
    }

    fun showInterstitialAd(onAdClosed: () -> Unit) {
        val prefs = getSharedPreferences("farewise_prefs", Context.MODE_PRIVATE)
        val adsEnabled = prefs.getBoolean("ads_enabled", true)
        if (!adsEnabled) {
            onAdClosed()
            return
        }

        val ad = mInterstitialAd
        if (ad != null) {
            ad.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    mInterstitialAd = null
                    loadInterstitialAd()
                    onAdClosed()
                }

                override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                    mInterstitialAd = null
                    loadInterstitialAd()
                    onAdClosed()
                }
            }
            ad.show(this)
        } else {
            loadInterstitialAd()
            onAdClosed()
        }
    }

    fun toggleBackgroundService(enabled: Boolean) {
        showInterstitialAd {
            executeToggleBackgroundService(enabled)
        }
    }

    private fun executeToggleBackgroundService(enabled: Boolean) {
        val intent = Intent(this, OverlayService::class.java)
        val prefs = getSharedPreferences("farewise_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("service_enabled", enabled).apply()
        
        val langStr = prefs.getString("lang", "en") ?: "en"
        val appLanguage = if (langStr == "si") AppLanguage.SINHALA else AppLanguage.ENGLISH

        if (enabled) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                viewModel.setServiceActive(true)
                val msg = getTxt("toast_service_active", appLanguage)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Cannot start background service: " + e.localizedMessage, Toast.LENGTH_LONG).show()
            }
        } else {
            try {
                stopService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            viewModel.setServiceActive(false)
            val msg = getTxt("toast_service_inactive", appLanguage)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // Handle post notifications result
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Notifications allowed! You will receive peak hire alerts.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Notifications denied. Please enable them to get real-time rates.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Upgrade previous test IDs in SharedPreferences to real default IDs
        val initPrefs = getSharedPreferences("farewise_prefs", Context.MODE_PRIVATE)
        val savedAppId = initPrefs.getString("admob_app_id", "")
        val savedInterstitialId = initPrefs.getString("admob_interstitial_id", "")
        val savedBannerId = initPrefs.getString("admob_banner_id", "")

        val editor = initPrefs.edit()
        var updated = false
        if (savedAppId == "ca-app-pub-3940256099942544~3347511713" || savedAppId == "ca-app-pub-7472113156561687~3347511713" || savedAppId.isNullOrEmpty()) {
            editor.putString("admob_app_id", "ca-app-pub-7472113156561687~3894706068")
            updated = true
        }
        if (savedInterstitialId == "ca-app-pub-3940256099942544/1033173712" || savedInterstitialId.isNullOrEmpty()) {
            editor.putString("admob_interstitial_id", "ca-app-pub-7472113156561687/1632907177")
            updated = true
        }
        if (savedBannerId == "ca-app-pub-3940256099942544/6300978111" || savedBannerId.isNullOrEmpty()) {
            editor.putString("admob_banner_id", "ca-app-pub-7472113156561687/4833785589")
            updated = true
        }
        if (updated) {
            editor.apply()
        }

        // Initialize AdMob SDK
        try {
            com.google.android.gms.ads.MobileAds.initialize(this) {}
        } catch (e: Exception) {
            e.printStackTrace()
        }
        loadInterstitialAd()

        val database = RideDatabase.getDatabase(this)
        val repository = RideRepository(database.rideDao())
        viewModel = ViewModelProvider(this, MainViewModelFactory(repository))[MainViewModel::class.java]

        // Sync service toggle with running state and SharedPreferences
        val isRunning = isServiceRunning(this, OverlayService::class.java)
        val prefs = getSharedPreferences("farewise_prefs", Context.MODE_PRIVATE)
        val serviceEnabled = prefs.getBoolean("service_enabled", false)
        
        if (serviceEnabled && !isRunning) {
            val intent = Intent(this, OverlayService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else if (!serviceEnabled && isRunning) {
            val intent = Intent(this, OverlayService::class.java)
            try {
                stopService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        viewModel.setServiceActive(serviceEnabled)

        // Ask for Notification Permissions on startup for Android 13+
        checkAndRequestNotificationPermission()

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = CyberBackground
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            DriverDashboardScreen(viewModel = viewModel)
                        }
                        AdMobBanner()
                    }
                }
            }
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Checking if service is currently active / started
    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    fun isNotificationServiceEnabled(context: Context): Boolean {
        val pkgName = context.packageName
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        if (!flat.isNullOrEmpty()) {
            val names = flat.split(":")
            for (name in names) {
                val cn = android.content.ComponentName.unflattenFromString(name)
                if (cn != null && cn.packageName == pkgName) {
                    return true
                }
            }
        }
        return false
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        // Method 1: Check Settings.Secure for configured enabled accessibility services
        try {
            val expectedComponentName = android.content.ComponentName(context, com.example.service.RideAccessibilityService::class.java).flattenToString()
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (!enabledServicesSetting.isNullOrEmpty()) {
                val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
                colonSplitter.setString(enabledServicesSetting)
                while (colonSplitter.hasNext()) {
                    val componentNameString = colonSplitter.next()
                    if (componentNameString.equals(expectedComponentName, ignoreCase = true)) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Method 2: Check AccessibilityManager enabled accessibility services list as fallback
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            val enabledServices = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            val pkgName = context.packageName
            for (service in enabledServices) {
                if (service.resolveInfo.serviceInfo.packageName == pkgName) {
                    return true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}

enum class AppLanguage {
    ENGLISH, SINHALA
}

fun getTxt(key: String, lang: AppLanguage): String {
    return when (lang) {
        AppLanguage.ENGLISH -> when (key) {
            "title" -> "FareWise LKR"
            "subtitle" -> "Driver Utility Companion"
            "onlinemonitor" -> "ONLINE MONITOR"
            "standby" -> "STANDBY"
            "tab_calculator" -> "Calculator"
            "tab_meter" -> "GPS Meter"
            "tab_simulator" -> "Simulator"
            "tab_logs" -> "Logs"
            "meter_title" -> "REAL-TIME GPS TAXI METER"
            "meter_start" -> "Start Trip"
            "meter_stop" -> "End & Save"
            "meter_pause" -> "Pause"
            "meter_resume" -> "Resume"
            "meter_reset" -> "Reset"
            "meter_save" -> "Save Trip"
            "meter_status_ready" -> "READY TO START"
            "meter_status_running" -> "TRIP IN PROGRESS"
            "meter_status_paused" -> "TRIP PAUSED"
            "meter_gps_search" -> "GPS: Searching Signal..."
            "meter_gps_active" -> "GPS: Signal Active ✅"
            "meter_gps_denied" -> "GPS: Permission Denied ❌"
            "meter_rates_title" -> "CUSTOMIZE FARE RATES"
            "meter_rate_first" -> "First Kilometer Rate (LKR)"
            "meter_rate_sub" -> "Subsequent KM Rate (LKR)"
            "meter_rate_wait" -> "Waiting Rate per Minute (LKR)"
            "meter_stats_dist" -> "DISTANCE"
            "meter_stats_dur" -> "DURATION"
            "meter_stats_wait" -> "WAIT TIME"
            "meter_stats_fare" -> "CURRENT FARE"
            "meter_sim_heading" -> "DEMO & TESTING CONTROLS"
            "meter_sim_dist_btn" -> "Simulate Motion (+0.5 km)"
            "meter_sim_wait_btn" -> "Simulate Idle Wait (+30s)"
            "toast_meter_saved" -> "Trip saved to History Logs!"
            "toast_meter_permission" -> "GPS Permission is required for Live Taxi Meter."
            "calc_title" -> "REAL-TIME FARE CALCULATOR"
            "calc_rate_lbl" -> "EARNINGS PER KILOMETER"
            "calc_lbl_fare" -> "Total Fare (LKR)"
            "calc_lbl_pickup" -> "Pickup Dist (km)"
            "calc_lbl_drop" -> "Drop Dist (km)"
            "calc_lbl_sum" -> "Sum of Dist"
            "calc_save_btn" -> "Save Log"
            "calc_preview_details" -> "LOG ROUTE PREVIEW DETAILS"
            "calc_pickup_addr" -> "Pickup Location Name"
            "calc_drop_addr" -> "Dropoff Location Name"
            "calc_desc_enter" -> "ENTER RIDE DETAILS BELOW"
            "calc_desc_high" -> "🔥 VERY PROFITABLE HIRE"
            "calc_desc_med" -> "✅ STANDARD MODERATE HIRE"
            "calc_desc_low" -> "⚠️ UNPROFITABLE ALERT"
            "sim_title" -> "BACKGROUND POPUP SIMULATOR"
            "sim_desc" -> "Since you want incoming hires to arrive like other apps (e.g. Uber/PickMe) in the background as a screen popup overlay, switch monitors 'ON', select a delay below, trigger a simulated ride, and switch to your home screen or another driver app to see it in action!"
            "sim_delay_title" -> "Simulation Timer Delay"
            "sim_presets_title" -> "SELECT A RIDE PRESET TO ARRIVE IN BACKGROUND:"
            "sim_instant" -> "Instant"
            "sim_preset_1" -> "Short Tuk Hire 🛺"
            "sim_preset_2" -> "Standard Car Ride 🚗"
            "sim_preset_3" -> "Long Distance Trip 🚙"
            "sim_preset_4" -> "Bad Traffic Hire ❌"
            "sim_lkr_rate" -> "LKR RATE"
            "sim_toast_delay" -> "Simulating in %d seconds... minimize now!"
            "toast_calc_saved" -> "Calculation saved to History Logs!"
            "toast_calc_invalid" -> "Please configure valid Fare and Distances first"
            "toast_service_active" -> "Background Ride Rate Monitor Activated!"
            "toast_service_inactive" -> "Background Monitor Deactivated."
            "logs_records" -> "RECORDS"
            "logs_clear" -> "Clear All"
            "logs_empty_title" -> "No Saved Ride Logs Yet"
            "logs_empty_desc" -> "Perform manual calculations or simulate background ride acceptances to populate driver logs."
            "logs_stat_sum" -> "DRIVER ACCEPTANCE SUMMARY"
            "logs_stat_fares" -> "TOTAL SAVED FARES"
            "logs_stat_avg" -> "AVERAGE LKR RATE"
            "logs_stat_trips" -> "ACCEPTED TRIP"
            "logs_item_total_fare" -> "TOTAL FARE"
            "logs_item_dist" -> "DISTANCE"
            "logs_item_rate" -> "LKR RATE"
            "creator_title" -> "DEVELOPER CREDIT"
            "creator_name" -> "Ishan Maduranga"
            "creator_role" -> "Lead System Utility Engineer"
            "permission_title" -> "Overlay Permission Required"
            "permission_desc" -> "Enable 'Draw over other apps' to see floating price-per-km popup widgets instantly."
            "permission_grant" -> "Grant"
            "notif_permission_title" -> "Notifications/Toasts Muted"
            "notif_permission_desc" -> "The system is suppressing notifications or toasts for this app. Go to settings, enable 'Show Notifications' and permissions to receive alerts."
            "notif_permission_button" -> "Settings"
            "listener_permission_title" -> "Notification Listener Access"
            "listener_permission_desc" -> "Enable 'Notification Listener Access' for this app so it can automatically detect ride offers from PickMe and Uber Driver in the background."
            "listener_permission_button" -> "Enable"
            "accessibility_permission_title" -> "Screen Overlay Reader"
            "accessibility_permission_desc" -> "Read ride details directly from Uber/PickMe screen:\n1. Click 'Enable' below.\n2. In Settings, select 'Installed apps' (or Installed Services / Downloaded Services).\n3. Find and turn ON 'Driver Utility Screen Reader'."
            "accessibility_permission_button" -> "Enable"
            "accessibility_restricted_notice" -> "If the setting is greyed out (Restricted Setting):\n1. Click 'App Settings' below.\n2. Tap 3-dots in top-right of App Info and click 'Allow restricted settings'.\n3. Click 'Enable' below to activate."
            "app_settings_button" -> "App Settings"
            "admob_settings_title" -> "💵 ADMOB AD EARNINGS SETUP"
            "admob_settings_desc" -> "To earn passive revenue from your app, connect your Google AdMob/AdSense publisher account. Enter your production Ad Unit IDs below. (Leave default IDs to run test ads first)."
            "admob_enable_ads" -> "Show Ads & Earn"
            "admob_app_id_lbl" -> "AdMob App ID"
            "admob_interstitial_id_lbl" -> "Interstitial Ad Unit ID"
            "admob_banner_id_lbl" -> "Banner Ad Unit ID"
            "admob_save_btn" -> "Save Ad Credentials"
            "toast_admob_saved" -> "AdMob credentials saved successfully! Reloading Ads..."
            else -> key
        }
        AppLanguage.SINHALA -> when (key) {
            "title" -> "ෆෙයාර්වයිස් LKR"
            "subtitle" -> "රියදුරු උපකාරක සේවය"
            "onlinemonitor" -> "සක්‍රීයයි"
            "standby" -> "ස්ටෑන්ඩ්බයි"
            "tab_calculator" -> "ගණකය"
            "tab_meter" -> "GPS මීටරය"
            "tab_simulator" -> "අනුකරණය"
            "tab_logs" -> "වාර්තා"
            "meter_title" -> "තත්‍ය කාලීන GPS ටැක්සි මීටරය"
            "meter_start" -> "ගමන අරඹන්න"
            "meter_stop" -> "අවසන් කර සුරකින්න"
            "meter_pause" -> "මදකට නවත්වන්න"
            "meter_resume" -> "නැවත අරඹන්න"
            "meter_reset" -> "ශූන්‍ය කරන්න"
            "meter_save" -> "ගමන සුරකින්න"
            "meter_status_ready" -> "ඇරඹීමට සූදානම්"
            "meter_status_running" -> "ධාවනය වෙමින් පවතී"
            "meter_status_paused" -> "මදකට නවත්වා ඇත"
            "meter_gps_search" -> "GPS: සංඥා සොයමින්..."
            "meter_gps_active" -> "GPS: සංඥා සක්‍රීයයි ✅"
            "meter_gps_denied" -> "GPS: අවසර නොමැත ❌"
            "meter_rates_title" -> "ගාස්තු අනුපාත වෙනස් කරන්න"
            "meter_rate_first" -> "පළමු කිලෝමීටරය සඳහා ගාස්තුව (LKR)"
            "meter_rate_sub" -> "ඉන්පසු කිලෝමීටර සඳහා ගාස්තුව (LKR)"
            "meter_rate_wait" -> "විනාඩියකට පොරොත්තු ගාස්තුව (LKR)"
            "meter_stats_dist" -> "දුර ප්‍රමාණය"
            "meter_stats_dur" -> "කාලය"
            "meter_stats_wait" -> "පොරොත්තු කාලය"
            "meter_stats_fare" -> "වත්මන් ගාස්තුව"
            "meter_sim_heading" -> "පරීක්ෂණ සහ නිරූපණ පාලක"
            "meter_sim_dist_btn" -> "ධාවනය අනුකරණය (+0.5 km)"
            "meter_sim_wait_btn" -> "පොරොත්තුව අනුකරණය (+තත් 30)"
            "toast_meter_saved" -> "ගමන ඉතිහාස වාර්තා වෙත සුරැකිණි!"
            "toast_meter_permission" -> "ටැක්සි මීටරය සඳහා GPS අවසරය අවශ්‍ය වේ."
            "calc_title" -> "තත්‍ය කාලීන ගාස්තු ගණකය"
            "calc_rate_lbl" -> "කිලෝමීටරයකට ඉපැයීම"
            "calc_lbl_fare" -> "මුළු ගාස්තුව (LKR)"
            "calc_lbl_pickup" -> "පික්අප් දුර (කි.මී.)"
            "calc_lbl_drop" -> "ඩ්‍රොප් දුර (කි.මී.)"
            "calc_lbl_sum" -> "මුළු දුර එකතුව"
            "calc_save_btn" -> "වාර්තාව සුරකින්න"
            "calc_preview_details" -> "මාර්ගයේ විස්තර ඇතුලත් කරන්න"
            "calc_pickup_addr" -> "ආරම්භක ස්ථානයේ නම"
            "calc_drop_addr" -> "ගමනාන්ත ස්ථානයේ නම"
            "calc_desc_enter" -> "ගමන් විස්තර පහතින් ඇතුලත් කරන්න"
            "calc_desc_high" -> "🔥 ඉතා ලාභදායී හයර් එකක්"
            "calc_desc_med" -> "✅ සාමාන්‍ය හයර් එකක්"
            "calc_desc_low" -> "⚠️ අලාභදායී හයර් අනතුරු ඇඟවීමක්"
            "sim_title" -> "පසුබිම් පොප්අප් අනුකරණය"
            "sim_desc" -> "වෙනත් ඇප්ස් (උදා: Uber/PickMe) මෙන් පසුබිමේ යන අතරතුර තිරයේ දිස්වන පොප්අප් එකක් ලෙස නව හයර් ගණනයන් දැක ගැනීමට, මොනිටරය 'සක්‍රීය' කර, ප්‍රමාද කාලය තෝරා, අනුකරණ බොත්තම ඔබා, ඔබගේ හෝම් ස්ක්‍රීන් එකට හෝ වෙනත් ඩ්‍රයිවර් ඇප් එකකට මාරු වන්න!"
            "sim_delay_title" -> "අනුකරණ කාල ප්‍රමාදය"
            "sim_presets_title" -> "පසුබිමේ අනුකරණය කිරීමට හයර් එකක් තෝරන්න:"
            "sim_instant" -> "ක්ෂණික"
            "sim_preset_1" -> "කෙටි ත්‍රීවිල් හයර් එකක් 🛺"
            "sim_preset_2" -> "සාමාන්‍ය කාර් හයර් එකක් 🚗"
            "sim_preset_3" -> "දිගු දුර ගමනක් 🚙"
            "sim_preset_4" -> "අධික වාහන තදබද හයර් එකක් ❌"
            "sim_lkr_rate" -> "LKR අනුපාතය"
            "sim_toast_delay" -> "තත්පර %dකින් පසුබිම් අනුකරණය ඇරඹේ... දැන් ඇප් එකෙන් ඉවත් වන්න!"
            "toast_calc_saved" -> "ගණනය කිරීම ඉතිහාස වාර්තා වෙත සුරැකිණි!"
            "toast_calc_invalid" -> "කරුණාකර පළමුව වලංගු ගාස්තුවක් සහ දුරක් ඇතුලත් කරන්න"
            "toast_service_active" -> "පසුබිම් ගාස්තු නිරීක්ෂකය සක්‍රීය කරන ලදී!"
            "toast_service_inactive" -> "පසුබිම් නිරීක්ෂකය අක්‍රීය කරන ලදී."
            "logs_records" -> "වාර්තා ගණන"
            "logs_clear" -> "සියල්ල මකන්න"
            "logs_empty_title" -> "තවමත් සුරකින ලද වාර්තා නොමැත"
            "logs_empty_desc" -> "වාර්තා ඇතුලත් කිරීමට හස්තීයව ගණනය කරන්න හෝ පසුබිම් හයර් අනුකරණය කරන්න."
            "logs_stat_sum" -> "රියදුරු පිළිගැනීමේ සාරාංශය"
            "logs_stat_fares" -> "මුළු උපයන ලද ගාස්තු"
            "logs_stat_avg" -> "සාමාන්ඤ LKR අනුපාතය"
            "logs_stat_trips" -> "පිළිගත් හයර් ප්‍රමාණය"
            "logs_item_total_fare" -> "මුළු ගාස්තුව"
            "logs_item_dist" -> "දුර ප්‍රමාණය"
            "logs_item_rate" -> "LKR අනුපාතය"
            "creator_title" -> "නිර්මාතෘ තොරතුරු"
            "creator_name" -> "ඉෂාන් මදුරංග"
            "creator_role" -> "ප්‍රධාන පද්ධති උපයෝගිතා ඉංජිනේරු"
            "permission_title" -> "උඩින් පෙන්වීමේ අවසරය අවශ්‍යයි"
            "permission_desc" -> "නව ගණනය කිරීම් පොප්අප් එකක් ලෙස තිරය මත දැකීමට 'වෙනත් ඇප්ස් මතුපිටින් පෙන්වන්න' (Draw over other apps) යන්න සක්‍රිය කරන්න."
            "permission_grant" -> "අවසර දෙන්න"
            "notif_permission_title" -> "දැනුම්දීම්/ටෝස්ට්ස් අක්‍රීය කර ඇත"
            "notif_permission_desc" -> "ඇප් එකෙහි දැනුම්දීම් හෝ ටෝස්ට් පද්ධතිය විසින් අක්‍රීය කර ඇත. අනතුරු ඇඟවීම් සහ විස්තර ලබා ගැනීමට සැකසුම් (Settings) වෙත ගොස් 'දැනුම්දීම් පෙන්වන්න' (Show Notifications) සහ අවසර ලබා දෙන්න."
            "notif_permission_button" -> "විවෘත කරන්න"
            "listener_permission_title" -> "දැනුම්දීම් නිරීක්ෂණ අවසරය"
            "listener_permission_desc" -> "PickMe සහ Uber ඇප්ස්වලින් පසුබිමේ පැමිණෙන හයර්ස් ස්වයංක්‍රීයව හඳුනා ගැනීමට 'Notification Access' (දැනුම්දීම් ප්‍රවේශය) අවසරය සක්‍රිය කරන්න."
            "listener_permission_button" -> "සක්‍රිය කරන්න"
            "accessibility_permission_title" -> "තිර කියවීමේ සහය අවසරය"
            "accessibility_permission_desc" -> "තිරයේ දිස්වන සැනින් හයර්ස් ගණනය කිරීමට:\n1. පහත 'අවසර දෙන්න' බොත්තම ඔබන්න.\n2. ඉන්පසු 'Installed apps' (හෝ Installed Services / Downloaded apps) යන්න තෝරන්න.\n3. 'Driver Utility Screen Reader' ක්ලික් කර එය සක්‍රිය (On) කරන්න."
            "accessibility_permission_button" -> "අවසර දෙන්න"
            "accessibility_restricted_notice" -> "අවසර දීමේ බොත්තම ක්‍රියා නොකරන්නේ නම් (Greyed Out / Restricted Setting):\n1. පහත 'ඇප් එකේ විස්තර' (App Settings) බොත්තම ඔබන්න.\n2. ඉහළ දකුණු කෙළවරේ ඇති තිත් 3 ඔබා 'Allow restricted settings' යන්න ක්ලික් කර ලොක් එක හරින්න.\n3. ඉන්පසු නැවත මෙහි පැමිණ 'අවසර දෙන්න' (Enable) බොත්තම ඔබන්න."
            "app_settings_button" -> "ඇප් එකේ විස්තර"
            "admob_settings_title" -> "💵 ADMOB දැන්වීම් ආදායම් සැකසුම්"
            "admob_settings_desc" -> "ඔබේ ඇප් එකෙන් අමතර ආදායමක් උපයා ගැනීමට, ඔබගේ Google AdMob/AdSense ගිණුම සම්බන්ධ කරන්න. ඔබගේ නිෂ්පාදන දැන්වීම් Unit IDs පහත ඇතුළත් කරන්න. (පරීක්ෂණ දැන්වීම් සඳහා පෙරනිමි අගයන් එලෙසම තබන්න)."
            "admob_enable_ads" -> "දැන්වීම් පෙන්වා මුදල් උපයන්න"
            "admob_app_id_lbl" -> "AdMob App ID එක"
            "admob_interstitial_id_lbl" -> "Interstitial දැන්වීම් ID එක"
            "admob_banner_id_lbl" -> "Banner දැන්වීම් ID එක"
            "admob_save_btn" -> "දැන්වීම් සැකසුම් සුරකින්න"
            "toast_admob_saved" -> "AdMob දැන්වීම් සැකසුම් සාර්ථකව සුරැකිණි! දැන්වීම් සක්‍රීය කෙරේ..."
            else -> key
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DriverDashboardScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val rides by viewModel.ridesState.collectAsStateWithLifecycle()
    val isServiceActive by viewModel.isServiceActive.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    val prefs = remember { context.getSharedPreferences("farewise_prefs", Context.MODE_PRIVATE) }
    var appLanguage by remember {
        mutableStateOf(
            if (prefs.getString("lang", "en") == "si") AppLanguage.SINHALA else AppLanguage.ENGLISH
        )
    }

    var activeTab by remember { mutableStateOf(0) } // 0: Live Calculator/Stats, 1: Background Simulator, 2: History

    var developerClicks by remember { mutableStateOf(0) }
    var isDeveloperUnlocked by remember { mutableStateOf(prefs.getBoolean("dev_mode_unlocked", false)) }

    var showWelcomeDialog by remember { mutableStateOf(true) }

    if (showWelcomeDialog) {
        Dialog(
            onDismissRequest = { /* Prevent dismiss on outside click */ },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .wrapContentHeight(),
                shape = RoundedCornerShape(24.dp),
                color = CyberSurface,
                border = BorderStroke(1.5.dp, CyberPrimary.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Welcome Riders",
                        color = CyberPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleLarge
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "භාවිතයට පෙර සියලු Permission enable කිරීමට වග බලාගන්න.",
                        color = Color(0xFFEF5350), // Red color
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Scrollable Area for the description
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item {
                                Text(
                                    text = "මෙම යෙදවුම හුදෙක් TAIX රියදුරු මහතුන්ගෙන් TAXI APP සමගම් වලින් රියදුරන්ට නොදැනුවත්ව සිදුවන අසාධාරණය පැහැදිලිව බලා ගැනිම සදහා නොමිලේ නිකුත් කල යෙදවුමකි\n\n" +
                                            "ඇප් එකේ ඇති ප්රධාන විශේෂාංග\n\n" +
                                            "01.GPS මීටරය (GPS Meter): සැබෑ ගමන් දුර සහ කාලය අනුව සජීවීව ධාවන ගාස්තුව (fare) ගණනය කරයි.\n\n" +
                                            "02.රයිඩ් සහකාරුවා (Ride Assistant): Uber සහ PickMe ඇප් වල රයිඩ් විස්තර හඳුනාගෙන Screen එක උඩින් (Overlay Popup) පෙන්වයි.\n\n" +
                                            "03.සිමියුලේටරය (Simulator): රයිඩ් පැමිණෙන ආකාරය පරීක්ෂා කිරීමට පසුබිම් දැනුම්දීම් අනුකරණය (simulate) කරයි.\n\n" +
                                            "04.වාර්තා සහ ඉතිහාසය (History): ඔබ උපයන මුදල් සහ ධාවන විස්තර සුරකින අතර ඒවායේ ප්රස්ථාර සහ වාර්තා පෙන්වයි.\n\n" +
                                            "ස්තුතිය. ",
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 14.sp,
                                    lineHeight = 22.sp,
                                    textAlign = TextAlign.Start
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Row showing Cancel & OK Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                (context as? Activity)?.finishAffinity()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = CyberSecondary
                            ),
                            border = BorderStroke(1.5.dp, CyberSecondary),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("welcome_cancel_button")
                        ) {
                            Text(
                                text = "Cancel",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = {
                                showWelcomeDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyberPrimary,
                                contentColor = CyberBackground
                            ),
                            modifier = Modifier
                                .weight(1f)
                                 .testTag("welcome_ok_button")
                        ) {
                            Text(
                                text = "OK",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    // Manual Calculation inputs
    var totalFareInput by remember { mutableStateOf("") }
    var pickupDistanceInput by remember { mutableStateOf("") }
    var dropDistanceInput by remember { mutableStateOf("") }

    // Quick address simulation fields
    var pickupAddressInput by remember {
        mutableStateOf(
            if (prefs.getString("lang", "en") == "si") "කොළඹ කොටුව දුම්රිය ස්ථානය" else "Colombo Fort Railway Station"
        )
    }
    var dropAddressInput by remember {
        mutableStateOf(
            if (prefs.getString("lang", "en") == "si") "බණ්ඩාරනායක ජාත්‍යන්තර ගුවන් තොටුපළ (BIA)" else "Bandaranaike Airport (BIA)"
        )
    }

    val decimalFormat = remember { DecimalFormat("0.00") }

    val notificationManager = remember { context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    var notificationsEnabled by remember { mutableStateOf(notificationManager.areNotificationsEnabled()) }

    var isNotificationListenerEnabled by remember { mutableStateOf((context as? MainActivity)?.isNotificationServiceEnabled(context) == true) }
    var isAccessibilityEnabled by remember { mutableStateOf((context as? MainActivity)?.isAccessibilityServiceEnabled(context) == true) }

    // Floating overlays details
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    // Dynamic Live Calculator values
    val manualFare = totalFareInput.toDoubleOrNull() ?: 0.0
    val manualPickup = pickupDistanceInput.toDoubleOrNull() ?: 0.0
    val manualDrop = dropDistanceInput.toDoubleOrNull() ?: 0.0
    val manualTotalDistance = manualPickup + manualDrop

    val manualEarningsPerKm = if (manualTotalDistance > 0) {
        manualFare / manualTotalDistance
    } else {
        0.0
    }

    // Toggle live monitoring service
    fun toggleBackgroundService(enabled: Boolean) {
        (context as? MainActivity)?.toggleBackgroundService(enabled)
    }

    // Periodic check for draw overlays permission and notification enabled when returning to app
    LaunchedEffect(Unit) {
        val mainActivity = context as? MainActivity
        while (true) {
            overlayGranted = Settings.canDrawOverlays(context)
            notificationsEnabled = notificationManager.areNotificationsEnabled()
            if (mainActivity != null) {
                isNotificationListenerEnabled = mainActivity.isNotificationServiceEnabled(context)
                isAccessibilityEnabled = mainActivity.isAccessibilityServiceEnabled(context)
            }
            delay(2000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // App header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(CyberSurface)
                .padding(vertical = 12.dp, horizontal = 20.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(CyberPrimary.copy(alpha = 0.15f), CircleShape)
                                .border(1.5.dp, CyberPrimary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Logo",
                                tint = CyberPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = getTxt("title", appLanguage),
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "2.0",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = CyberPrimary,
                                    modifier = Modifier
                                        .background(CyberPrimary.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = getTxt("subtitle", appLanguage),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                    }

                    // Quick Mode Switch: Online/Offline Monitor
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (isServiceActive) getTxt("onlinemonitor", appLanguage) else getTxt("standby", appLanguage),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (isServiceActive) CyberPrimary else Color.Gray
                        )
                        Switch(
                            checked = isServiceActive,
                            onCheckedChange = { toggleBackgroundService(it) },
                            modifier = Modifier.testTag("service_toggle"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyberBackground,
                                checkedTrackColor = CyberPrimary,
                                uncheckedThumbColor = Color.LightGray,
                                uncheckedTrackColor = CyberSurfaceVariant
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Language selection & Creator credit row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Creator credit
                    Row(
                        modifier = Modifier.clickable {
                            developerClicks++
                            if (developerClicks >= 7) {
                                val newState = !isDeveloperUnlocked
                                isDeveloperUnlocked = newState
                                prefs.edit().putBoolean("dev_mode_unlocked", newState).apply()
                                developerClicks = 0
                                val msg = if (newState) {
                                    if (appLanguage == AppLanguage.SINHALA) "💵 AdMob දැන්වීම් සැකසුම් සක්‍රීය විය!" else "💵 AdMob Earnings Setup unlocked!"
                                } else {
                                    if (appLanguage == AppLanguage.SINHALA) "💵 AdMob දැන්වීම් සැකසුම් අක්‍රිය විය!" else "💵 AdMob Earnings Setup locked!"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Creator",
                            tint = CyberPrimary.copy(alpha = 0.9f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (appLanguage == AppLanguage.SINHALA) "නිර්මාතෘ: ඉෂාන් මදුරංග" else "Creator: Ishan Maduranga",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.LightGray
                        )
                    }

                    // Language toggler pill
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(CyberSurfaceVariant)
                            .clickable {
                                appLanguage = if (appLanguage == AppLanguage.ENGLISH) AppLanguage.SINHALA else AppLanguage.ENGLISH
                                prefs.edit().putString("lang", if (appLanguage == AppLanguage.SINHALA) "si" else "en").apply()
                                
                                // update address presets dynamically
                                if (appLanguage == AppLanguage.SINHALA && pickupAddressInput == "Colombo Fort Railway Station") {
                                    pickupAddressInput = "කොළඹ කොටුව දුම්රිය ස්ථානය"
                                    dropAddressInput = "බණ්ඩාරනායක ජාත්‍යන්තර ගුවන් තොටුපළ (BIA)"
                                } else if (appLanguage == AppLanguage.ENGLISH && pickupAddressInput == "කොළඹ කොටුව දුම්රිය ස්ථානය") {
                                    pickupAddressInput = "Colombo Fort Railway Station"
                                    dropAddressInput = "Bandaranaike Airport (BIA)"
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Select Language",
                            tint = CyberSecondary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (appLanguage == AppLanguage.SINHALA) "සිංහල 🇱🇰" else "English 🇬🇧",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Custom Navigation Tabs
        TabRow(
            selectedTabIndex = activeTab,
            containerColor = CyberSurface,
            contentColor = CyberSecondary,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[activeTab]),
                    color = CyberSecondary
                )
            },
            divider = { HorizontalDivider(color = CyberSurfaceVariant) }
        ) {
            Tab(
                selected = activeTab == 0,
                onClick = { activeTab = 0 },
                modifier = Modifier.testTag("tab_calculator"),
                text = { Text(getTxt("tab_calculator", appLanguage), fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.Create, contentDescription = null) }
            )
            Tab(
                selected = activeTab == 1,
                onClick = { activeTab = 1 },
                modifier = Modifier.testTag("tab_meter"),
                text = { Text(getTxt("tab_meter", appLanguage), fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.LocationOn, contentDescription = null) }
            )
            Tab(
                selected = activeTab == 2,
                onClick = { activeTab = 2 },
                modifier = Modifier.testTag("tab_simulator"),
                text = { Text(getTxt("tab_simulator", appLanguage), fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.Notifications, contentDescription = null) }
            )
            Tab(
                selected = activeTab == 3,
                onClick = { activeTab = 3 },
                modifier = Modifier.testTag("tab_history"),
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(getTxt("tab_logs", appLanguage), fontWeight = FontWeight.Bold)
                        if (rides.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Badge(containerColor = CyberSecondary) {
                                Text(rides.size.toString(), color = CyberBackground, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                },
                icon = { Icon(Icons.Default.List, contentDescription = null) }
            )
        }

        // Display Alert if Accessibility Service is disabled
        AnimatedVisibility(
            visible = !isAccessibilityEnabled && isServiceActive,
            enter = slideInVertically() + fadeIn(),
            exit = slideOutVertically() + fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                colors = CardDefaults.cardColors(containerColor = ProfitLow.copy(alpha = 0.15f)),
                border = BorderStroke(1.dp, ProfitLow),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Accessibility Support",
                            tint = ProfitLow,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = getTxt("accessibility_permission_title", appLanguage),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = getTxt("accessibility_permission_desc", appLanguage),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Restricted Settings bypass notice block
                            Surface(
                                color = Color(0xFFFF5722).copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, Color(0xFFFF5722).copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = getTxt("accessibility_restricted_notice", appLanguage),
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color(0xFFFFCCBC),
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Button to open Android App details (App Info) directly
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = android.net.Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            },
                            border = BorderStroke(1.dp, ProfitLow),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                text = getTxt("app_settings_button", appLanguage),
                                style = MaterialTheme.typography.labelSmall.copy(color = ProfitLow, fontWeight = FontWeight.Bold)
                            )
                        }

                        // Button to open Settings to activate service
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        val componentName = android.content.ComponentName(context, com.example.service.RideAccessibilityService::class.java).flattenToString()
                                        putExtra(":settings:fragment_args_key", componentName)
                                        putExtra(":settings:show_fragment_args", true)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    try {
                                        val fallbackIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        context.startActivity(fallbackIntent)
                                    } catch (ex: Exception) {
                                        ex.printStackTrace()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ProfitLow),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = getTxt("accessibility_permission_button", appLanguage),
                                style = MaterialTheme.typography.labelSmall.copy(color = CyberBackground, fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }
        }

        // Display Alert if Draw overlays permission is not granted
        AnimatedVisibility(
            visible = !overlayGranted && isServiceActive,
            enter = slideInVertically() + fadeIn(),
            exit = slideOutVertically() + fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                colors = CardDefaults.cardColors(containerColor = ProfitLow.copy(alpha = 0.15f)),
                border = BorderStroke(1.dp, ProfitLow),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Permission needed",
                            tint = ProfitLow,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = getTxt("permission_title", appLanguage),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Text(
                                text = getTxt("permission_desc", appLanguage),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ProfitLow),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(getTxt("permission_grant", appLanguage), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        }

        // Display Alert if Notifications are disabled/suppressed
        AnimatedVisibility(
            visible = !notificationsEnabled,
            enter = slideInVertically() + fadeIn(),
            exit = slideOutVertically() + fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                colors = CardDefaults.cardColors(containerColor = ProfitLow.copy(alpha = 0.15f)),
                border = BorderStroke(1.dp, ProfitLow),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = "Notifications Muted",
                            tint = ProfitLow,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = getTxt("notif_permission_title", appLanguage),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Text(
                                text = getTxt("notif_permission_desc", appLanguage),
                                modifier = Modifier.testTag("notif_permission_desc_text"),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                            } else {
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ProfitLow),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(getTxt("notif_permission_button", appLanguage), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        }

        // Display Alert if Notification Listener is disabled
        AnimatedVisibility(
            visible = !isNotificationListenerEnabled && isServiceActive,
            enter = slideInVertically() + fadeIn(),
            exit = slideOutVertically() + fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                colors = CardDefaults.cardColors(containerColor = ProfitLow.copy(alpha = 0.15f)),
                border = BorderStroke(1.dp, ProfitLow),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = "Notification Access",
                            tint = ProfitLow,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = getTxt("listener_permission_title", appLanguage),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Text(
                                text = getTxt("listener_permission_desc", appLanguage),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray
                            )
                        }
                    }

                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ProfitLow),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(getTxt("listener_permission_button", appLanguage), style = MaterialTheme.typography.labelSmall.copy(color = CyberBackground, fontWeight = FontWeight.Bold))
                    }
                }
            }
        }

        // Main Tab Content Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(CyberBackground)
        ) {
            when (activeTab) {
                0 -> {
                    // Calculator tab
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Earnings stats summary
                        item {
                            QuickStatsCard(rides = rides, lang = appLanguage)
                        }

                        // AdMob configuration & setup card
                        if (isDeveloperUnlocked) {
                            item {
                                AdMobConfigCard(lang = appLanguage)
                            }
                        }

                        // Calculator Card
                        item {
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
                                        text = getTxt("calc_title", appLanguage),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        ),
                                        color = CyberSecondary
                                    )

                                    Spacer(modifier = Modifier.height(14.dp))

                                    // Display live calculation result
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color.Black.copy(alpha = 0.4f))
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = getTxt("calc_rate_lbl", appLanguage),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.Gray
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            
                                            val rateColor = when {
                                                manualEarningsPerKm >= 100.0 -> ProfitHigh
                                                manualEarningsPerKm >= 60.0 -> ProfitMedium
                                                manualEarningsPerKm > 0.0 -> ProfitLow
                                                else -> Color.LightGray
                                            }

                                            Text(
                                                text = if (manualEarningsPerKm > 0) "Rs. ${decimalFormat.format(manualEarningsPerKm)}/km" else "Rs. 0.00/km",
                                                style = MaterialTheme.typography.headlineLarge.copy(
                                                    fontWeight = FontWeight.Black
                                                ),
                                                color = rateColor
                                            )

                                            Spacer(modifier = Modifier.height(6.dp))

                                            val metricText = when {
                                                manualEarningsPerKm >= 100.0 -> getTxt("calc_desc_high", appLanguage)
                                                manualEarningsPerKm >= 60.0 -> getTxt("calc_desc_med", appLanguage)
                                                manualEarningsPerKm > 0.0 -> getTxt("calc_desc_low", appLanguage)
                                                else -> getTxt("calc_desc_enter", appLanguage)
                                            }

                                            Text(
                                                text = metricText,
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                                                color = rateColor
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Inputs in row and columns
                                    OutlinedTextField(
                                        value = totalFareInput,
                                        onValueChange = { totalFareInput = it },
                                        label = { Text(getTxt("calc_lbl_fare", appLanguage)) },
                                        placeholder = { Text("e.g. 1200") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, tint = CyberPrimary) },
                                        singleLine = true,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("fare_input"),
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

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = pickupDistanceInput,
                                            onValueChange = { pickupDistanceInput = it },
                                            label = { Text(getTxt("calc_lbl_pickup", appLanguage)) },
                                            placeholder = { Text("e.g. 1.5") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = CyberSecondary) },
                                            singleLine = true,
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("pickup_input"),
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

                                        OutlinedTextField(
                                            value = dropDistanceInput,
                                            onValueChange = { dropDistanceInput = it },
                                            label = { Text(getTxt("calc_lbl_drop", appLanguage)) },
                                            placeholder = { Text("e.g. 8.5") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = CyberSecondary) },
                                            singleLine = true,
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("drop_input"),
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
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${getTxt("calc_lbl_sum", appLanguage)}: ${decimalFormat.format(manualTotalDistance)} km",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                            color = Color.LightGray
                                        )

                                        Button(
                                            onClick = {
                                                if (manualFare > 0 && manualTotalDistance > 0) {
                                                    val request = RideRequest(
                                                        totalFare = manualFare,
                                                        pickupDistance = manualPickup,
                                                        dropDistance = manualDrop,
                                                        pickupAddress = pickupAddressInput.ifBlank { "Manual Pickup" },
                                                        dropAddress = dropAddressInput.ifBlank { "Manual Drop" },
                                                        status = "ACCEPTED"
                                                    )
                                                    viewModel.addManualRide(request)
                                                    
                                                    // Reset Inputs
                                                    totalFareInput = ""
                                                    pickupDistanceInput = ""
                                                    dropDistanceInput = ""
                                                    
                                                    val msg = getTxt("toast_calc_saved", appLanguage)
                                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                } else {
                                                    val msg = getTxt("toast_calc_invalid", appLanguage)
                                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.testTag("save_calc_button"),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = CyberPrimary,
                                                contentColor = CyberBackground
                                            ),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.Done, contentDescription = null)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(getTxt("calc_save_btn", appLanguage), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        // Detailed Location names (under inputs)
                        item {
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
                                        text = getTxt("calc_preview_details", appLanguage),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color.Gray
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    OutlinedTextField(
                                        value = pickupAddressInput,
                                        onValueChange = { pickupAddressInput = it },
                                        label = { Text(getTxt("calc_pickup_addr", appLanguage)) },
                                        singleLine = true,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedBorderColor = CyberPrimary,
                                            unfocusedBorderColor = CyberSurfaceVariant,
                                            focusedLabelColor = CyberPrimary,
                                            unfocusedLabelColor = Color.Gray
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    OutlinedTextField(
                                        value = dropAddressInput,
                                        onValueChange = { dropAddressInput = it },
                                        label = { Text(getTxt("calc_drop_addr", appLanguage)) },
                                        singleLine = true,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedBorderColor = CyberPrimary,
                                            unfocusedBorderColor = CyberSurfaceVariant,
                                            focusedLabelColor = CyberPrimary,
                                            unfocusedLabelColor = Color.Gray
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                1 -> {
                    // GPS Meter tab
                    com.example.ui.GpsMeterScreen(
                        appLanguage = appLanguage,
                        onSaveTrip = { fare, dist, routeNotes, status ->
                            viewModel.addManualRide(
                                com.example.data.RideRequest(
                                    totalFare = fare,
                                    pickupDistance = 0.0,
                                    dropDistance = dist,
                                    pickupAddress = "GPS Meter Start",
                                    dropAddress = "GPS Meter End",
                                    status = "ACCEPTED"
                                )
                            )
                        }
                    )
                }

                2 -> {
                    // Simulator tab
                    var simulationDelaySecs by remember { mutableStateOf(3) } // 3s default

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Simulator introduction
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, CyberSurfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = getTxt("sim_title", appLanguage),
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                                        color = CyberSecondary
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = getTxt("sim_desc", appLanguage),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.LightGray
                                    )
                                    
                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Delay configurations
                                    Text(
                                        text = "${getTxt("sim_delay_title", appLanguage)}: ${simulationDelaySecs}s",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf(0, 3, 5, 10).forEach { delayVal ->
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (simulationDelaySecs == delayVal) CyberSecondary else CyberSurfaceVariant)
                                                    .clickable { simulationDelaySecs = delayVal }
                                                    .padding(vertical = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = if (delayVal == 0) getTxt("sim_instant", appLanguage) else "${delayVal}s",
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = if (simulationDelaySecs == delayVal) CyberBackground else Color.White
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Presets
                        item {
                            Text(
                                text = getTxt("sim_presets_title", appLanguage),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
                                color = Color.Gray,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }

                        // Preset cards with localized landmarks and titles
                        val presets = listOf(
                            Triple(
                                getTxt("sim_preset_1", appLanguage),
                                Triple(250.0, 0.5, 1.8),
                                Pair(
                                    if (appLanguage == AppLanguage.SINHALA) "කොළඹ ජාතික රෝහල" else "Colombo National Hospital",
                                    if (appLanguage == AppLanguage.SINHALA) "කොළඹ නගර ශාලාව" else "Colombo Town Hall"
                                )
                            ),
                            Triple(
                                getTxt("sim_preset_2", appLanguage),
                                Triple(1400.0, 2.5, 12.0),
                                Pair(
                                    if (appLanguage == AppLanguage.SINHALA) "දෙහිවල සත්වෝද්‍යානය" else "Dehiwala Zoo Entrance",
                                    if (appLanguage == AppLanguage.SINHALA) "නුගේගොඩ ගුවන් පාලම" else "Nugegoda Flyover"
                                )
                            ),
                            Triple(
                                getTxt("sim_preset_3", appLanguage),
                                Triple(8500.0, 4.0, 60.0),
                                Pair(
                                    if (appLanguage == AppLanguage.SINHALA) "කඩුවෙල අධිවේගී පිවිසුම" else "Kaduwela Express Interchange",
                                    if (appLanguage == AppLanguage.SINHALA) "ගාලු මුවදොර පිටිය (Galle Face)" else "Galle Face Green"
                                )
                            ),
                            Triple(
                                getTxt("sim_preset_4", appLanguage),
                                Triple(300.0, 3.2, 2.0),
                                Pair(
                                    if (appLanguage == AppLanguage.SINHALA) "පිටකොටුව රතු පල්ලිය" else "Pettah Red Mosque",
                                    if (appLanguage == AppLanguage.SINHALA) "ලිබර්ටි ප්ලාසා" else "Liberty Plaza"
                                )
                            ),
                            Triple(
                                if (appLanguage == AppLanguage.SINHALA) "Uber Tuk (තිර පිටපත)" else "Uber Tuk (Screenshot Match)",
                                Triple(420.0, 0.4, 2.4),
                                Pair(
                                    "Kalutara North, Station Road, KT1 2GC, Wadduwa",
                                    if (appLanguage == AppLanguage.SINHALA) "Cyril Road, Cyril Rd, වාද්දුව" else "Cyril Road, Cyril Rd, Wadduwa"
                                )
                            )
                        )

                        items(presets) { preset ->
                            val title = preset.first
                            val fare = preset.second.first
                            val pickDist = preset.second.second
                            val dropDist = preset.second.third
                            val epk = fare / (pickDist + dropDist)
                            val pickAddr = preset.third.first
                            val dropAddr = preset.third.second

                            val rateColor = when {
                                epk >= 100.0 -> ProfitHigh
                                epk >= 60.0 -> ProfitMedium
                                else -> ProfitLow
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (!isServiceActive) {
                                            // auto trigger active
                                            toggleBackgroundService(true)
                                        }
                                        val toastMsg = getTxt("sim_toast_delay", appLanguage).format(simulationDelaySecs)
                                        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()

                                        coroutineScope.launch {
                                            delay(simulationDelaySecs * 1000L)
                                            val i = Intent(context, OverlayService::class.java).apply {
                                                putExtra("total_fare", fare)
                                                putExtra("pickup_dist", pickDist)
                                                putExtra("drop_dist", dropDist)
                                                putExtra("pickup_addr", pickAddr)
                                                putExtra("drop_addr", dropAddr)
                                            }
                                            try {
                                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                                    context.startForegroundService(i)
                                                } else {
                                                    context.startService(i)
                                                }
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        }
                                    },
                                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, CyberSurfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = Color.White
                                        )
                                        Text(
                                            text = "Fare: Rs. $fare | Total Dist: ${pickDist + dropDist}km",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.LightGray
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "$pickAddr ➔ $dropAddr",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.Gray,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = getTxt("sim_lkr_rate", appLanguage),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.Gray
                                        )
                                        Text(
                                            text = "Rs. ${decimalFormat.format(epk)}/km",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                                            color = rateColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                3 -> {
                    // History Logs tab
                    if (rides.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Empty History",
                                    tint = CyberSurfaceVariant,
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = getTxt("logs_empty_title", appLanguage),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = getTxt("logs_empty_desc", appLanguage),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.Gray,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${getTxt("logs_records", appLanguage)} (${rides.size})",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                                    color = Color.Gray
                                )

                                TextButton(
                                    onClick = { viewModel.clearHistory() },
                                    colors = ButtonDefaults.textButtonColors(contentColor = ProfitLow)
                                ) {
                                    Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(getTxt("logs_clear", appLanguage), fontWeight = FontWeight.Bold)
                                }
                            }

                            LazyColumn(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(rides) { ride ->
                                    val rate = ride.earningsPerKm
                                    val rateColor = when {
                                        rate >= 100.0 -> ProfitHigh
                                        rate >= 60.0 -> ProfitMedium
                                        else -> ProfitLow
                                    }

                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = CyberSurface),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, CyberSurfaceVariant)
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    val statusIcon = if (ride.status == "ACCEPTED") Icons.Default.Done else Icons.Default.Close
                                                    val statusColor = if (ride.status == "ACCEPTED") ProfitHigh else ProfitLow
                                                    
                                                    Icon(
                                                        imageVector = statusIcon,
                                                        contentDescription = ride.status,
                                                        tint = statusColor,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    
                                                    val localizedStatus = if (ride.status == "ACCEPTED") {
                                                        if (appLanguage == AppLanguage.SINHALA) "පිළිගත් හයර් එකක්" else "ACCEPTED"
                                                    } else {
                                                        if (appLanguage == AppLanguage.SINHALA) "ප්‍රතික්ෂේපිත" else "REJECTED"
                                                    }
                                                    Text(
                                                        text = localizedStatus,
                                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                        color = statusColor
                                                    )
                                                }

                                                IconButton(
                                                    onClick = { viewModel.deleteRide(ride.id) },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Delete,
                                                        contentDescription = "Delete",
                                                        tint = Color.Gray,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(10.dp))

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text(
                                                        text = getTxt("logs_item_total_fare", appLanguage),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = Color.Gray
                                                    )
                                                    Text(
                                                        text = "Rs. ${ride.totalFare}",
                                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                        color = Color.White
                                                    )
                                                }

                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text(
                                                        text = getTxt("logs_item_dist", appLanguage),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = Color.Gray
                                                    )
                                                    Text(
                                                        text = "${decimalFormat.format(ride.pickupDistance + ride.dropDistance)} km",
                                                        style = MaterialTheme.typography.titleMedium,
                                                        color = Color.LightGray
                                                    )
                                                }

                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text(
                                                        text = getTxt("logs_item_rate", appLanguage),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = Color.Gray
                                                    )
                                                    Text(
                                                        text = "Rs. ${decimalFormat.format(rate)}/km",
                                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                                                        color = rateColor
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))

                                            HorizontalDivider(color = CyberSurfaceVariant.copy(alpha = 0.5f))

                                            Spacer(modifier = Modifier.height(8.dp))

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Info,
                                                    contentDescription = null,
                                                    tint = CyberSecondary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "${ride.pickupAddress} ➔ ${ride.dropAddress}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color.LightGray,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Stats panel inside Calculator / Live Dashboard
@Composable
fun QuickStatsCard(rides: List<RideRequest>, lang: AppLanguage) {
    val acceptedRides = remember(rides) { rides.filter { it.status == "ACCEPTED" } }
    val totalEarnings = remember(acceptedRides) { acceptedRides.sumOf { it.totalFare } }
    val totalDist = remember(acceptedRides) { acceptedRides.sumOf { it.pickupDistance + it.dropDistance } }
    val avgRate = remember(totalEarnings, totalDist) {
        if (totalDist > 0) totalEarnings / totalDist else 0.0
    }

    val df = remember { DecimalFormat("0.00") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CyberSurface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CyberSurfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = getTxt("logs_stat_sum", lang),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = getTxt("logs_stat_fares", lang),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    Text(
                        text = "Rs. ${df.format(totalEarnings)}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = CyberPrimary
                    )
                }

                VerticalDivider(
                    color = CyberSurfaceVariant,
                    modifier = Modifier
                        .height(36.dp)
                        .padding(horizontal = 14.dp)
                )

                Column(modifier = Modifier.weight(1.1f)) {
                    Text(
                        text = getTxt("logs_stat_avg", lang),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    Text(
                        text = "Rs. ${df.format(avgRate)}/km",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = CyberSecondary
                    )
                }

                VerticalDivider(
                    color = CyberSurfaceVariant,
                    modifier = Modifier
                        .height(36.dp)
                        .padding(horizontal = 14.dp)
                )

                Column(modifier = Modifier.weight(0.9f)) {
                    Text(
                        text = getTxt("logs_stat_trips", lang),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    Text(
                        text = if (lang == AppLanguage.SINHALA) "හයර් ${acceptedRides.size}" else "${acceptedRides.size} Rides",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun AdMobBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("farewise_prefs", Context.MODE_PRIVATE) }
    val adsEnabled = prefs.getBoolean("ads_enabled", true)
    
    if (!adsEnabled) return

    val configuredBannerId = prefs.getString("admob_banner_id", "ca-app-pub-7472113156561687/4833785589") ?: "ca-app-pub-7472113156561687/4833785589"
    val forceTestAds = prefs.getBoolean("admob_force_test_ads", false)
    val isEmulatorDevice = remember { isEmulator(context) }

    val adUnitIdToUse = if (forceTestAds || isEmulatorDevice) {
        "ca-app-pub-3940256099942544/6300978111" // Test Banner ID
    } else {
        configuredBannerId
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.runtime.key(adUnitIdToUse) {
            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { ctx ->
                    com.google.android.gms.ads.AdView(ctx).apply {
                        setAdSize(com.google.android.gms.ads.AdSize.BANNER)
                        adUnitId = adUnitIdToUse
                        adListener = object : com.google.android.gms.ads.AdListener() {
                            override fun onAdFailedToLoad(loadAdError: com.google.android.gms.ads.LoadAdError) {
                                Log.e("AdMob", "Banner failed to load: ${loadAdError.message} (Code: ${loadAdError.code})")
                            }

                            override fun onAdLoaded() {
                                Log.d("AdMob", "Banner loaded successfully with ID: $adUnitIdToUse")
                            }
                        }
                        try {
                            val adRequest = com.google.android.gms.ads.AdRequest.Builder().build()
                            loadAd(adRequest)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                },
                update = {}
            )
        }
    }
}

@Composable
fun AdMobConfigCard(lang: AppLanguage) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("farewise_prefs", Context.MODE_PRIVATE) }
    
    var adsEnabled by remember { mutableStateOf(prefs.getBoolean("ads_enabled", true)) }
    var appIdInput by remember { mutableStateOf(prefs.getString("admob_app_id", "ca-app-pub-7472113156561687~3894706068") ?: "ca-app-pub-7472113156561687~3894706068") }
    var interstitialIdInput by remember { mutableStateOf(prefs.getString("admob_interstitial_id", "ca-app-pub-7472113156561687/1632907177") ?: "ca-app-pub-7472113156561687/1632907177") }
    var bannerIdInput by remember { mutableStateOf(prefs.getString("admob_banner_id", "ca-app-pub-7472113156561687/4833785589") ?: "ca-app-pub-7472113156561687/4833785589") }
    var forceTestAds by remember { mutableStateOf(prefs.getBoolean("admob_force_test_ads", false)) }

    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CyberSurface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (adsEnabled) CyberSecondary.copy(alpha = 0.5f) else CyberSurfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Earnings Icon",
                        tint = if (adsEnabled) CyberSecondary else Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = getTxt("admob_settings_title", lang),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                        color = if (adsEnabled) Color.White else Color.Gray
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand/Collapse",
                    tint = Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = getTxt("admob_settings_desc", lang),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Switch Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = getTxt("admob_enable_ads", lang),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                        Switch(
                            checked = adsEnabled,
                            onCheckedChange = {
                                adsEnabled = it
                                prefs.edit().putBoolean("ads_enabled", it).apply()
                                if (it) {
                                    (context as? MainActivity)?.loadInterstitialAd()
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyberBackground,
                                checkedTrackColor = CyberSecondary,
                                uncheckedThumbColor = Color.LightGray,
                                uncheckedTrackColor = CyberSurfaceVariant
                            )
                        )
                    }

                    if (adsEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))

                        // App ID field
                        OutlinedTextField(
                            value = appIdInput,
                            onValueChange = { appIdInput = it },
                            label = { Text(getTxt("admob_app_id_lbl", lang)) },
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = CyberSecondary,
                                unfocusedBorderColor = CyberSurfaceVariant,
                                focusedLabelColor = CyberSecondary,
                                unfocusedLabelColor = Color.Gray
                            )
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Interstitial ID field
                        OutlinedTextField(
                            value = interstitialIdInput,
                            onValueChange = { interstitialIdInput = it },
                            label = { Text(getTxt("admob_interstitial_id_lbl", lang)) },
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = CyberSecondary,
                                unfocusedBorderColor = CyberSurfaceVariant,
                                focusedLabelColor = CyberSecondary,
                                unfocusedLabelColor = Color.Gray
                            )
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Banner ID field
                        OutlinedTextField(
                            value = bannerIdInput,
                            onValueChange = { bannerIdInput = it },
                            label = { Text(getTxt("admob_banner_id_lbl", lang)) },
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = CyberSecondary,
                                unfocusedBorderColor = CyberSurfaceVariant,
                                focusedLabelColor = CyberSecondary,
                                unfocusedLabelColor = Color.Gray
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Force Test Ads Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (lang == AppLanguage.SINHALA) "ටෙස්ට් දැන්වීම් පෙන්වන්න (Test Ads)" else "Force Test Ads (For Testing)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                            Switch(
                                checked = forceTestAds,
                                onCheckedChange = {
                                    forceTestAds = it
                                    prefs.edit().putBoolean("admob_force_test_ads", it).apply()
                                    (context as? MainActivity)?.loadInterstitialAd()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = CyberBackground,
                                    checkedTrackColor = CyberSecondary,
                                    uncheckedThumbColor = Color.LightGray,
                                    uncheckedTrackColor = CyberSurfaceVariant
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Save button
                        Button(
                            onClick = {
                                prefs.edit()
                                    .putString("admob_app_id", appIdInput.trim())
                                    .putString("admob_interstitial_id", interstitialIdInput.trim())
                                    .putString("admob_banner_id", bannerIdInput.trim())
                                    .putBoolean("admob_force_test_ads", forceTestAds)
                                    .apply()
                                Toast.makeText(context, getTxt("toast_admob_saved", lang), Toast.LENGTH_SHORT).show()
                                (context as? MainActivity)?.loadInterstitialAd()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = CyberSecondary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = getTxt("admob_save_btn", lang),
                                color = CyberBackground,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

fun isEmulator(context: android.content.Context): Boolean {
    val brand = android.os.Build.BRAND
    val device = android.os.Build.DEVICE
    val model = android.os.Build.MODEL
    val product = android.os.Build.PRODUCT
    val hardware = android.os.Build.HARDWARE
    val fingerprint = android.os.Build.FINGERPRINT

    return (fingerprint.startsWith("generic")
            || model.contains("google_sdk")
            || model.contains("Emulator")
            || model.contains("Android SDK built for x86")
            || (brand.startsWith("generic") && device.startsWith("generic"))
            || "google_sdk" == product
            || hardware.contains("goldfish")
            || hardware.contains("ranchu")
            || model.contains("Cuttlefish")
            || product.contains("sdk_gphone"))
}

