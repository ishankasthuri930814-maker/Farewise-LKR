package com.example

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log

class MyApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        bypassHiddenApiRestrictions()
    }

    override fun onCreate() {
        super.onCreate()
    }

    private fun bypassHiddenApiRestrictions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                Log.d("BypassHiddenApi", "Attempting hidden API bypass on Android version: ${Build.VERSION.SDK_INT}")
                val clazz = Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass")
                
                // Log class methods for validation
                try {
                    val methods = clazz.declaredMethods
                    for (method in methods) {
                        Log.d("BypassHiddenApi", "Found method: ${method.name} with params: ${method.parameterTypes.joinToString { it.simpleName }}")
                    }
                } catch (ex: Throwable) {
                    Log.w("BypassHiddenApi", "Failed to list methods", ex)
                }

                val addExemptionsMethod = clazz.getDeclaredMethod("addExemptions", Array<String>::class.java)
                
                // Pass the Array of String properly as a single object to match the vararg parameter
                val result = addExemptionsMethod.invoke(null, arrayOf("L") as Any)
                Log.d("BypassHiddenApi", "Bypass result: $result")
                Log.d("BypassHiddenApi", "Successfully bypassed hidden api checks!")
            } catch (e: Throwable) {
                Log.w("BypassHiddenApi", "Bypassing hidden api failed with details", e)
            }
        }
    }
}
