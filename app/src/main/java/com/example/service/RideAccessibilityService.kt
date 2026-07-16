package com.example.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Intent
import android.content.Context
import android.util.Log

class RideAccessibilityService : AccessibilityService() {

    private var lastParsedFare = 0.0
    private var lastParsedDistance = 0.0
    private var lastParseTime = 0L
    private var lastActivePackage = ""

    private val debounceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingRunnable: Runnable? = null

    override fun onDestroy() {
        pendingRunnable?.let { debounceHandler.removeCallbacks(it) }
        pendingRunnable = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val prefs = getSharedPreferences("farewise_prefs", Context.MODE_PRIVATE)
        val isServiceEnabled = prefs.getBoolean("service_enabled", false)
        if (!isServiceEnabled) {
            return
        }

        if (com.example.MainActivity.isAppInForeground) {
            return
        }

        // Track the package name of the active app that triggered the event, excluding our own app and system layers
        val pkg = event.packageName?.toString()?.lowercase() ?: ""
        if (pkg.isNotEmpty() && !pkg.contains("com.example") && !pkg.contains("aistudio") && !pkg.contains("systemui") && pkg != "android") {
            lastActivePackage = pkg
        }

        val eventType = event.eventType

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Process instantly on screen changes for ultimate response time
            pendingRunnable?.let { debounceHandler.removeCallbacks(it) }
            pendingRunnable = null
            processCurrentScreen()
        } else if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Debounce content changes to collect multi-pass rendering updates before parsing (ensures we get the final loaded state)
            pendingRunnable?.let { debounceHandler.removeCallbacks(it) }
            val runnable = Runnable {
                processCurrentScreen()
            }
            pendingRunnable = runnable
            debounceHandler.postDelayed(runnable, 80) // 80ms is imperceptible but highly effective at letting layouts populate
        }
    }

    private fun processCurrentScreen() {
        val prefs = getSharedPreferences("farewise_prefs", Context.MODE_PRIVATE)
        val isServiceEnabled = prefs.getBoolean("service_enabled", false)
        if (!isServiceEnabled) {
            return
        }

        if (com.example.MainActivity.isAppInForeground) {
            return
        }

        val activePkg = lastActivePackage

        // 1. Inspect all active interactive windows (highly robust when other apps are in the foreground)
        val activeWindows = windows
        var processedTargetWindow = false
        if (!activeWindows.isNullOrEmpty()) {
            for (window in activeWindows) {
                val root = window.root ?: continue
                val pkg = root.packageName?.toString()?.lowercase() ?: ""
                
                // Skip our own app
                if (pkg == packageName || pkg.contains("com.example") || pkg.contains("aistudio")) {
                    continue
                }
                
                try {
                    val textList = mutableListOf<String>()
                    findTextsInNode(root, textList)
                    if (textList.isNotEmpty()) {
                        val fullScreenText = textList.joinToString(" ")
                        val fullScreenTextLower = fullScreenText.lowercase()
                        
                        // Check if the current window, last active package, or screen content matches Uber/PickMe
                        val isUber = pkg.contains("uber") || pkg.contains("ubercab") || 
                                     activePkg.contains("uber") || activePkg.contains("ubercab") ||
                                     fullScreenTextLower.contains("uber")
                        val isPickMe = pkg.contains("pickme") || pkg.contains("bhasha") || 
                                       activePkg.contains("pickme") || activePkg.contains("bhasha") ||
                                       fullScreenTextLower.contains("pickme") || fullScreenTextLower.contains("වාරිකාව") || fullScreenTextLower.contains("වාරිකය")
                        
                        val isOurApp = fullScreenTextLower.contains("welcome riders") || 
                                       fullScreenTextLower.contains("permission enable කිරීමට") ||
                                       fullScreenTextLower.contains("gps meter") ||
                                       fullScreenTextLower.contains("farewise")

                        if (!isOurApp && (isUber || isPickMe)) {
                            Log.d("RideAccessibilityService", "Detected ride app in active window: pkg=$pkg, activePkg=$activePkg. Extracted text size=${textList.size}")
                            parseScreenContent(textList, fullScreenText)
                            processedTargetWindow = true
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e("RideAccessibilityService", "Error traversing active window: $pkg", e)
                }
            }
        }

        // 2. Fallback to using rootInActiveWindow
        if (!processedTargetWindow) {
            val rootNode = rootInActiveWindow ?: return
            val rootPackage = rootNode.packageName?.toString()?.lowercase() ?: ""
            
            if (rootPackage == packageName || rootPackage.contains("com.example") || rootPackage.contains("aistudio")) {
                return
            }
            
            try {
                val textList = mutableListOf<String>()
                findTextsInNode(rootNode, textList)
                if (textList.isNotEmpty()) {
                    val fullScreenText = textList.joinToString(" ")
                    val fullScreenTextLower = fullScreenText.lowercase()

                    // Prevent parsing our own app screens
                    if (fullScreenTextLower.contains("welcome riders") || 
                        fullScreenTextLower.contains("permission enable කිරීමට") ||
                        fullScreenTextLower.contains("gps meter") ||
                        fullScreenTextLower.contains("farewise")) {
                        return
                    }

                    val isUber = rootPackage.contains("uber") || rootPackage.contains("ubercab") ||
                                 activePkg.contains("uber") || activePkg.contains("ubercab") ||
                                 fullScreenTextLower.contains("uber")

                    val isPickMe = rootPackage.contains("pickme") || rootPackage.contains("bhasha") ||
                                   activePkg.contains("pickme") || activePkg.contains("bhasha") ||
                                   fullScreenTextLower.contains("pickme") || fullScreenTextLower.contains("වාරිකාව") || fullScreenTextLower.contains("වාරිකය")

                    if (isUber || isPickMe) {
                        Log.d("RideAccessibilityService", "Detected ride app in rootInActiveWindow. Package=$rootPackage, activePkg=$activePkg. Extracted text list size=${textList.size}")
                        parseScreenContent(textList, fullScreenText)
                    }
                }
            } catch (e: Exception) {
                Log.e("RideAccessibilityService", "Error traversing rootInActiveWindow", e)
            }
        }
    }

    private fun findTextsInNode(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null) return
        
        try {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrBlank()) {
                list.add(text)
            }
            
            val contentDesc = node.contentDescription?.toString()?.trim()
            if (!contentDesc.isNullOrBlank() && contentDesc != text) {
                list.add(contentDesc)
            }
        } catch (e: Exception) {
            // Ignore errors in reading current node
        }
        
        try {
            val childCount = node.childCount
            for (i in 0 until childCount) {
                try {
                    val child = node.getChild(i)
                    if (child != null) {
                        findTextsInNode(child, list)
                        // Note: Omit manual child.recycle() on modern Android versions
                        // to prevent tree invalidation during recursive traversal.
                    }
                } catch (e: Exception) {
                    // Ignore individual child errors to let other siblings be traversed
                }
            }
        } catch (e: Exception) {
            // Ignore childCount retrieval errors
        }
    }

    private fun isEarningsOrSummary(textList: List<String>, index: Int): Boolean {
        val selfIgnoreKeywords = listOf(
            "ඉපැයීම්", "earnings", "wallet", "balance", "incentives", "incentive", "පසුම්බිය", "ශේෂය", "දිරිදීමනා"
        )
        
        val self = textList[index].lowercase()
        for (keyword in selfIgnoreKeywords) {
            if (self.contains(keyword)) {
                Log.d("RideAccessibilityService", "Ignoring fare candidate because self '$self' contains ignore keyword '$keyword'")
                return true
            }
        }
        
        // Check adjacent elements in an immediate window (-1 to +1) for stronger indicators of earnings
        val start = maxOf(0, index - 1)
        val end = minOf(textList.size - 1, index + 1)
        
        val strictNeighborKeywords = listOf(
            "earnings", "ඉපැයීම්", "wallet", "balance", "පසුම්බිය", "ශේෂය", "incentives", "incentive"
        )
        
        for (j in start..end) {
            if (j == index) continue
            val neighbor = textList[j].lowercase()
            for (keyword in strictNeighborKeywords) {
                if (neighbor.contains(keyword)) {
                    Log.d("RideAccessibilityService", "Ignoring fare candidate because neighbor '$neighbor' contains ignore keyword '$keyword'")
                    return true
                }
            }
        }
        return false
    }

    private fun extractFareFromString(rawStr: String): Double {
        val strClean = rawStr.replace(",", "").replace('\u00A0', ' ').trim()
        val prefixFareRegex = Regex("(?:LKR|Rs|RS|rs|රු|රුපියල්|ரூ)\\s*\\.?\\s*([\\d.]+)", RegexOption.IGNORE_CASE)
        val suffixFareRegex = Regex("([\\d.]+)\\s*\\.?\\s*(?:LKR|Rs|RS|rs|රු|රුපියල්|ரூ|/=)", RegexOption.IGNORE_CASE)

        val prefixMatch = prefixFareRegex.find(strClean)
        if (prefixMatch != null) {
            val f = prefixMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            if (f > 0.0) return f
        }

        val suffixMatch = suffixFareRegex.find(strClean)
        if (suffixMatch != null) {
            val f = suffixMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            if (f > 0.0) return f
        }

        // Fallback: If it's a bare number with no currency, check if it's a potential fare (e.g., "450" or "1200.00")
        // typically between 120.0 and 15000.0, and must be purely numeric (no other alphabetic characters)
        if (strClean.matches(Regex("\\d+(?:\\.\\d+)?"))) {
            val f = strClean.toDoubleOrNull() ?: 0.0
            if (f >= 120.0 && f <= 15000.0) {
                return f
            }
        }

        return 0.0
    }

    private fun parseScreenContent(textList: List<String>, rawTextContent: String) {
        val rawTextLower = rawTextContent.lowercase()
        
        // 1. Detect standby screens and active offers
        val hasStandbyIndicators = rawTextLower.contains("සොයමින්") || 
                                   rawTextLower.contains("දැනට එපමණයි") || 
                                   rawTextLower.contains("දිස්වනු ඇත") || 
                                   rawTextLower.contains("ඔබ ඔන්ලයින්") || 
                                   rawTextLower.contains("you're online") || 
                                   rawTextLower.contains("you are online") ||
                                   rawTextLower.contains("ඔන්ලයින් යන්න") ||
                                   rawTextLower.contains("go online") ||
                                   rawTextLower.contains("ඕෆ්ලයින්") ||
                                   rawTextLower.contains("offline") ||
                                   rawTextLower.contains("අද") ||
                                   rawTextLower.contains("today") ||
                                   rawTextLower.contains("මුල් පිටුව") ||
                                   rawTextLower.contains("home") ||
                                   rawTextLower.contains("ස්ථාන සඳහා සොයන්න") ||
                                   rawTextLower.contains("search for places") ||
                                   rawTextLower.contains("earnings") ||
                                   rawTextLower.contains("ඉපැයීම්") ||
                                   rawTextLower.contains("incentives") ||
                                   rawTextLower.contains("incentive")
                                   
        val hasActiveOffer = rawTextLower.contains("ලබාගන්න") || 
                             rawTextLower.contains("වාරිකාව") || 
                             rawTextLower.contains("වාරිකය") || 
                             rawTextLower.contains("වාරික") || 
                             rawTextLower.contains("සවාරිය") || 
                             rawTextLower.contains("සවාරි") || 
                             rawTextLower.contains("පිළිගන්න") || 
                             rawTextLower.contains("පිලිගන්න") || 
                             rawTextLower.contains("ප්‍රතික්ෂේප") || 
                             rawTextLower.contains("ප්‍රතික්‍ෂේප") || 
                             rawTextLower.contains("ප්රතික්ෂේප") || 
                             rawTextLower.contains("ප්රතික්ෂේප කරන්න") || 
                             rawTextLower.contains("මඟහරින්න") || 
                             rawTextLower.contains("මගහරින්න") || 
                             rawTextLower.contains("නව සවාරිය") ||
                             rawTextLower.contains("සවාරියක්") ||
                             rawTextLower.contains("වෙන්කරවා ගැනීම") ||
                             rawTextLower.contains("පැමිණීමේ") ||
                             rawTextLower.contains("පික්අප්") ||
                             rawTextLower.contains("ඩ්‍රොප්") ||
                             rawTextLower.contains("ගැළපීම") ||
                             rawTextLower.contains("ගැළපීම්") ||
                             rawTextLower.contains("ගැලපීම") ||
                             rawTextLower.contains("ගැලපීම්") ||
                             rawTextLower.contains("මුදලින් ගෙවීම") ||
                             rawTextLower.contains("කාඩ්පතෙන් ගෙවීම") ||
                             rawTextLower.contains("ගෙවීම්") ||
                             rawTextLower.contains("ගෙවීම") ||
                             rawTextLower.contains("මුදලින්") ||
                             rawTextLower.contains("කාඩ්පතෙන්") ||
                             rawTextLower.contains("trip radar") || 
                             rawTextLower.contains("get ride") || 
                             rawTextLower.contains("new ride") || 
                             rawTextLower.contains("accept ride") || 
                             rawTextLower.contains("accept?") || 
                             rawTextLower.contains("match") || 
                             rawTextLower.contains("choose") ||
                             rawTextLower.contains("accept") ||
                             rawTextLower.contains("decline") ||
                             rawTextLower.contains("dismiss") ||
                             rawTextLower.contains("cancel") ||
                             rawTextLower.contains("reject") ||
                             rawTextLower.contains("confirm") ||
                             rawTextLower.contains("request") ||
                             rawTextLower.contains("trip") ||
                             rawTextLower.contains("away") ||
                             rawTextLower.contains("fare") ||
                             Regex("\\b\\d+(?:\\.\\d+)?\\s*(?:min|mins|km|mi|miles|minutes|මිනිත්තු|මිනින්තු|කිමී|කි\\.\\s*මී\\.)\\s*(?:away|දුරින්)?\\b").containsMatchIn(rawTextLower) ||
                             Regex("(?:කි\\.\\s*මී\\.|කිමී|කිලෝමීටර්|කිලෝ\\s*මීටර්|මීටර්|මී|විනාඩි|මිනි)\\s*\\d+(?:\\.\\d+)?(?:ක්)?", RegexOption.IGNORE_CASE).containsMatchIn(rawTextLower)
                             
        Log.d("RideAccessibilityService", "Standby indicators=$hasStandbyIndicators, Active offer signature=$hasActiveOffer")

        // 2. Fetch Fare using the precise candidate matching loop (including adjacent combined nodes)
        var parsedFare = 0.0
        val fareCandidates = mutableListOf<Pair<String, Int>>() // Pair of candidate string and its original textList index
        
        for (i in textList.indices) {
            val rawStr = textList[i]
            fareCandidates.add(Pair(rawStr, i))
            
            // Combine with next item to handle split currency/amount nodes
            if (i < textList.size - 1) {
                val combined = rawStr + " " + textList[i + 1]
                fareCandidates.add(Pair(combined, i))
            }
            
            // Combine with previous item
            if (i > 0) {
                val combined = textList[i - 1] + " " + rawStr
                fareCandidates.add(Pair(combined, i))
            }
        }
        
        for (candidate in fareCandidates) {
            val rawStr = candidate.first
            val origIndex = candidate.second
            val f = extractFareFromString(rawStr)
            if (f > 0.0) {
                // Check if this candidate is Today's Earnings or Summary
                if (isEarningsOrSummary(textList, origIndex)) {
                    Log.d("RideAccessibilityService", "Skipped candidate fare $f from string '$rawStr' at index $origIndex because it is categorized as earnings/summary.")
                    continue
                }
                
                // Keep the largest valid fare found on screen!
                if (f > parsedFare) {
                    parsedFare = f
                    Log.d("RideAccessibilityService", "Found larger valid ride offer fare candidate: $parsedFare from '$rawStr' (derived from index $origIndex)")
                }
            }
        }

        if (parsedFare == 0.0) {
            Log.d("RideAccessibilityService", "No valid fare extracted from active ride app window (checked ${textList.size} candidates).")
            return
        }

        // 3. Fetch Distances
        val distancesList = mutableListOf<Double>()
        val cleanText = rawTextContent.replace('\u00A0', ' ')
            .replace('\u2007', ' ')
            .replace('\u202F', ' ')
            .replace('\r', ' ')
            .replace('\n', ' ')
        val textNoCommas = cleanText.replace(",", "")
        
        // Number-First patterns
        val kmRegex = Regex("([\\d.]+)\\s*(?:km|kilometers|කි\\.?\\s*මී\\.?|කිලෝමීටර්|කිලෝ\\s*මීටර්|කிலோமீட்டர்|கி\\.மீ)", RegexOption.IGNORE_CASE)
        val meterRegex = Regex("([\\d.]+)\\s*(?:meters|මීටර්|මී|மீட்டர்|மீ)\\b", RegexOption.IGNORE_CASE)
        val standaloneMRegex = Regex("([\\d.]+)\\s*\\bm\\b", RegexOption.IGNORE_CASE)
        val miRegex = Regex("([\\d.]+)\\s*(?:mi|mile|miles)\\b", RegexOption.IGNORE_CASE)

        // Unit-First patterns (very common in Sinhala e.g. කි.මී. 1.8ක්, විනාඩි 1ක්)
        val kmUnitFirstRegex = Regex("(?:කි\\.?\\s*මී\\.?|කිලෝමීටර්|කිලෝ\\s*මීටර්|கிலோமீட்டர்|கி\\.மீ)\\s*([\\d.]+)(?:ක්)?", RegexOption.IGNORE_CASE)
        val meterUnitFirstRegex = Regex("(?:මීටර්|මී|மீட்டர்|மீ)\\s*([\\d.]+)(?:ක්)?", RegexOption.IGNORE_CASE)

        // Parse Number-First Kilometers
        val kmMatches = kmRegex.findAll(textNoCommas).toList()
        for (m in kmMatches) {
            val dist = m.groupValues[1].toDoubleOrNull()
            if (dist != null && dist >= 0.0) {
                distancesList.add(dist)
                Log.d("RideAccessibilityService", "Found distance (km, number-first): $dist from ${m.value}")
            }
        }

        // Parse Unit-First Kilometers
        val kmUnitFirstMatches = kmUnitFirstRegex.findAll(textNoCommas).toList()
        for (m in kmUnitFirstMatches) {
            val dist = m.groupValues[1].toDoubleOrNull()
            if (dist != null && dist >= 0.0) {
                distancesList.add(dist)
                Log.d("RideAccessibilityService", "Found distance (km, unit-first): $dist from ${m.value}")
            }
        }

        // Parse Number-First Meters
        val meterMatches = meterRegex.findAll(textNoCommas).toList()
        for (m in meterMatches) {
            val dist = m.groupValues[1].toDoubleOrNull()
            if (dist != null && dist >= 0.0) {
                val distKm = dist * 0.001
                distancesList.add(distKm)
                Log.d("RideAccessibilityService", "Found distance (meters, number-first): $dist ($distKm km) from ${m.value}")
            }
        }

        // Parse Unit-First Meters
        val meterUnitFirstMatches = meterUnitFirstRegex.findAll(textNoCommas).toList()
        for (m in meterUnitFirstMatches) {
            val dist = m.groupValues[1].toDoubleOrNull()
            if (dist != null && dist >= 0.0) {
                val distKm = dist * 0.001
                distancesList.add(distKm)
                Log.d("RideAccessibilityService", "Found distance (meters, unit-first): $dist ($distKm km) from ${m.value}")
            }
        }

        // Parse Standalone M Suffix
        val mMatches = standaloneMRegex.findAll(textNoCommas).toList()
        for (m in mMatches) {
            val dist = m.groupValues[1].toDoubleOrNull()
            if (dist != null && dist >= 100.0) { // Require at least 100 meters to prevent parsing time indicators like "5 m away" (5 minutes) as meters
                val distKm = dist * 0.001
                distancesList.add(distKm)
                Log.d("RideAccessibilityService", "Found distance (m suffix): $dist ($distKm km) from ${m.value}")
            }
        }

        // Parse Miles
        val miMatches = miRegex.findAll(textNoCommas).toList()
        for (m in miMatches) {
            val dist = m.groupValues[1].toDoubleOrNull()
            if (dist != null && dist >= 0.0) {
                val distKm = dist * 1.60934
                distancesList.add(distKm)
                Log.d("RideAccessibilityService", "Found distance (miles): $dist ($distKm km) from ${m.value}")
            }
        }

        var pickupDist = 0.0
        var dropDist = 0.0

        // Deduplicate identical or near-identical values to avoid layout hierarchy duplicates polluting our results
        val uniqueDistances = mutableListOf<Double>()
        for (d in distancesList) {
            if (uniqueDistances.none { Math.abs(it - d) < 0.05 }) {
                uniqueDistances.add(d)
            }
        }

        if (uniqueDistances.isNotEmpty()) {
            if (uniqueDistances.size == 1) {
                dropDist = uniqueDistances[0]
            } else {
                pickupDist = uniqueDistances[0]
                dropDist = uniqueDistances[1]
            }
        }

        val totalDistance = pickupDist + dropDist

        // We trigger if:
        // 1. We have a valid parsed fare AND
        // 2. We are confident it is an active offer (either we have active offer keywords on screen, OR we have a valid non-zero travel distance on screen and no history indicators)
        var shouldTrigger = false
        if (parsedFare > 0.0) {
            val hasHistoryIndicators = rawTextLower.contains("history") || 
                                       rawTextLower.contains("ඉතිහාසය") || 
                                       rawTextLower.contains("summary") || 
                                       rawTextLower.contains("විස්තර") || 
                                       rawTextLower.contains("details") ||
                                       rawTextLower.contains("පසුගිය") ||
                                       rawTextLower.contains("past trips")

            if (hasHistoryIndicators) {
                Log.d("RideAccessibilityService", "Ignoring fare $parsedFare because it looks like a history/summary screen.")
            } else if (hasActiveOffer) {
                shouldTrigger = true
                Log.d("RideAccessibilityService", "Triggering because we found active offer keywords and fare $parsedFare.")
            } else if (totalDistance > 0.0) {
                shouldTrigger = true
                Log.d("RideAccessibilityService", "Triggering because we found travel distance $totalDistance and fare $parsedFare.")
            } else {
                Log.d("RideAccessibilityService", "Ignoring fare $parsedFare: No travel distance and no active offer keywords found.")
            }
        }

        if (!shouldTrigger) {
            return
        }

        // 4. Extract Addresses
        val (pickupAddr, dropAddr) = extractAddresses(textList)

        // 5. Trigger Overlay if Fare is successfully parsed
        val now = System.currentTimeMillis()
        // Avoid duplicate triggers for identical requests within 8 seconds
        if (parsedFare == lastParsedFare && totalDistance == lastParsedDistance && (now - lastParseTime) < 8000) {
            Log.d("RideAccessibilityService", "Ignoring identical ride request trigger matching: Fare=$parsedFare, Distance=$totalDistance")
            return
        }

        lastParsedFare = parsedFare
        lastParsedDistance = totalDistance
        lastParseTime = now

        Log.d("RideAccessibilityService", "[Accessibility] Triggering overlay! Fare=$parsedFare PickupDist=$pickupDist DropDist=$dropDist PickupAddr='$pickupAddr' DropAddr='$dropAddr'")

        val intent = Intent(applicationContext, OverlayService::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("total_fare", parsedFare)
            putExtra("pickup_dist", pickupDist)
            putExtra("drop_dist", dropDist)
            putExtra("pickup_addr", pickupAddr)
            putExtra("drop_addr", dropAddr)
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("RideAccessibilityService", "Failed to start OverlayService", e)
        }
    }

    private fun extractAddresses(textList: List<String>): Pair<String, String> {
        val candidates = mutableListOf<String>()
        val currencySymbols = listOf("lkr", "rs", "රු", "ру", "rupees", "/=")
        val uiKeywords = listOf(
            "වාරිකා", "ලබාගන්න", "දැනට", "ගමන්", "match", "accept", "reject", "cancel", "decline", "dismiss", "online", "standby",
            "google", "map", "සිතියම", "profile", "image", "url", "icon", "button", "switch", "avatar", "menu", "search", "ස්ථාන", 
            "මුල් පිටුව", "home", "අද", "today", "yesterday", "tomorrow", "earnings", "ඉපැයීම්", "wallet", "පසුම්බිය", "incentive", 
            "incentives", "ලොග්", "log", "status", "තත්ත්වය", "history", "ඉතිහාසය",
            "ගෙවීම්", "ගෙවීම", "මුදලින්", "cash", "card", "pay", "payment",
            "ගැලපීම", "ගැලපීම්", "ගැළපීම", "ගැළපීම්"
        )

        for (rawStr in textList) {
            // Strip parentheses structures like (2mins away, 0.4 km) or (5 min, 1.79 km)
            val str = rawStr.replace(Regex("\\(.*\\)"), "").trim()
            if (str.length <= 4) continue
            
            val strLower = str.lowercase()
            
            // Check currency
            var hasCurrency = false
            for (curr in currencySymbols) {
                if (strLower.contains(curr)) {
                    hasCurrency = true
                    break
                }
            }
            if (hasCurrency) continue

            // Check UI keywords
            var hasUi = false
            for (ui in uiKeywords) {
                if (strLower.contains(ui)) {
                    hasUi = true
                    break
                }
            }
            if (hasUi) continue

            // Ignore if it's purely numeric/symbolic (e.g. "+ 100", "4.74", "5.00") or has no alphabetic characters
            if (!str.any { it.isLetter() }) continue

            // Ignore time duration patterns that are not stripped by parentheses (e.g. "5 mins", "38 mins")
            val timingKeywords = listOf("min", "mins", "minute", "minutes", "විනාඩි", "මිනි", "hr", "hrs", "hour", "hours", "පැය")
            var isTiming = false
            for (tk in timingKeywords) {
                if (strLower.contains(tk)) {
                    isTiming = true
                    break
                }
            }
            if (isTiming) continue

            // Ignore if length is too short
            if (str.length <= 6) continue

            candidates.add(str)
        }

        val pickup = if (candidates.isNotEmpty()) candidates[0] else "Incoming Ride Request"
        val drop = if (candidates.size > 1) candidates[1] else "Auto-Calculated Route"
        return Pair(pickup, drop)
    }

    override fun onInterrupt() {}
}
