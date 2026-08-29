package com.example.nfcdoorcard

import android.annotation.SuppressLint
import android.app.Application
import android.database.Cursor
import android.net.Uri
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import java.util.Locale

class XposedEntry : XposedModule() {

    private val TAG = "NfcUIDSim"
    private val CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.config/settings")
    private var lastConfig: SimConfig? = null
    private var lastFetchTime: Long = 0
    private val CACHE_MS = 2000 
    private val nativeManagers = mutableSetOf<Any>()

    override fun onPackageLoaded(lp: XposedModuleInterface.PackageLoadedParam) {
        val pkg = lp.packageName
        val cl = lp.defaultClassLoader

        // Universal Health Check: Hook MainActivity to report activity
        if (pkg == "com.example.nfcdoorcard") {
            try {
                val main = cl.loadClass("com.example.nfcdoorcard.MainActivity")
                main.declaredMethods.forEach { method ->
                    if (method.name == "isXposedActive") {
                        hook(method).intercept { true }
                    }
                }
            } catch (ignored: Exception) {}
        }

        // Broad NFC Service Identification (Adapting for Oplus/OnePlus variants)
        if (!pkg.contains("nfc", ignoreCase = true)) return
        
        Log.e(TAG, "NFC-HIJACK: Armed in process: $pkg (Thread: ${Thread.currentThread().name})")

        // 1. Capture and Hook Native Managers
        val managerClasses = arrayOf(
            "com.android.nfc.dhimpl.NxpNativeNfcManager",
            "com.android.nfc.dhimpl.StNativeNfcManager",
            "com.android.nfc.dhimpl.NativeNfcManager",
            "com.android.nfc.dhimpl.NativeNfcManager\$NativeNfcManagerExt"
        )
        for (className in managerClasses) {
            try {
                val clazz = Class.forName(className, false, cl)
                
                // Track instances for proactive locking
                clazz.declaredConstructors.forEach { constructor ->
                    hook(constructor).intercept { chain ->
                        val inst = chain.proceed()
                        if (inst != null) {
                            Log.e(TAG, "NFC-HIJACK: Captured new manager instance: ${inst.javaClass.name}")
                            nativeManagers.add(inst)
                        }
                        inst
                    }
                }

                // Core UID simulation hooks
                clazz.declaredMethods.forEach { m ->
                    val name = m.name.lowercase(Locale.ROOT)
                    if (name.contains("sethcetypeaconfig") || name.contains("dosethcetypeaconfig")) {
                        applyHceHijack(m)
                    }
                }
            } catch (ignored: Exception) {}
        }

        // 2. Global State Event Monitors (Detecting "Backstabs")
        val serviceClasses = arrayOf("com.android.nfc.NfcService", "com.oplus.nfc.OplusNfcService")
        for (sc in serviceClasses) {
            try {
                val svc = Class.forName(sc, false, cl)
                svc.declaredMethods.forEach { m ->
                    val name = m.name.lowercase(Locale.ROOT)
                    // Triggered by Screen, AirMode, Wallet Switching
                    if (name.contains("routing") || name.contains("screenstate") || name.contains("applyconfig")) {
                        hook(m).intercept { chain ->
                            lastFetchTime = 0 // Expire cache
                            val res = chain.proceed()
                            Log.e(TAG, "NFC-HIJACK: System event ($name) intercepted. Re-enforcing sim lock.")
                            enforceState()
                            res
                        }
                    }
                }
            } catch (ignored: Exception) {}
        }
    }

    private fun enforceState() {
        val config = fetchConfig()
        if (!config.active || config.uid == null) return

        nativeManagers.forEach { manager ->
            try {
                manager.javaClass.declaredMethods.forEach { m ->
                    if (m.name.contains("setHceTypeAConfig", ignoreCase = true)) {
                        m.isAccessible = true
                        // Invoking this will pass through our applyHceHijack interceptor
                        m.invoke(manager, true, null, null, null)
                    }
                }
            } catch (e: Exception) {
                // Log.e(TAG, "Proactive lock fail", e)
            }
        }
    }

    private fun applyHceHijack(method: Method) {
        try {
            hook(method).intercept { chain ->
                val config = fetchConfig()
                if (config.active && config.uid != null) {
                    val args = chain.args.toMutableList()
                    
                    // Force state: [enable=true, uid, sak, atqa]
                    args[0] = true 
                    args[1] = hexToBytes(config.uid)
                    if (args.size >= 3) args[2] = hexToBytes(config.sak ?: "08")
                    if (args.size >= 4) args[3] = hexToBytes(config.atqa ?: "0400")
                    
                    Log.e(TAG, "NFC-HIJACK: [STRICT LOCK] Forced UID ${config.uid} via ${method.declaringClass.simpleName}.${method.name}")
                    chain.proceed(args.toTypedArray())
                } else {
                    chain.proceed()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Hook fail: ${method.name}", t)
        }
    }

    data class SimConfig(val active: Boolean, val uid: String?, val sak: String?, val atqa: String?)

    @SuppressLint("Range")
    private fun fetchConfig(): SimConfig {
        val now = System.currentTimeMillis()
        if (now - lastFetchTime < CACHE_MS && lastConfig != null) return lastConfig!!

        var cursor: Cursor? = null
        try {
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as? Application
            cursor = app?.contentResolver?.query(CONFIG_URI, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val map = mutableMapOf<String, String>()
                do {
                    map[cursor.getString(0)] = cursor.getString(1)
                } while (cursor.moveToNext())

                lastConfig = SimConfig(
                    active = map["simulation_enabled"] == "true",
                    uid = map["uid"],
                    sak = map["sak"],
                    atqa = map["atqa"]
                )
                lastFetchTime = now
            }
        } catch (t: Throwable) {
        } finally {
            cursor?.close()
        }
        return lastConfig ?: SimConfig(false, null, null, null)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val s = hex.replace(":", "").replace(" ", "").replace("0x", "")
        val len = s.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
        }
        return data
    }
}
