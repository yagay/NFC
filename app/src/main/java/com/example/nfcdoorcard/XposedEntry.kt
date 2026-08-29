package com.example.nfcdoorcard

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import java.lang.reflect.Method
import java.util.Locale

class XposedEntry(base: XposedInterface, param: ModuleLoadedParam) : XposedModule(base, param) {

    companion object {
        private const val TAG = "NfcUIDSim"
        private val CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.config/settings")
        private const val CACHE_MS = 2000L
    }

    private var lastConfig: SimConfig? = null
    private var lastFetchTime: Long = 0
    private val nativeManagers = mutableSetOf<Any>()

    init {
        log("MODULE: XposedEntry instantiated (Modern API 102), process=${param.processName}")
        Log.e(TAG, "MODULE: XposedEntry instantiated (Modern API 102), process=${param.processName}")
    }

    override fun onPackageLoaded(lp: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(lp)
        val pkg = lp.packageName
        val cl = lp.defaultClassLoader

        Log.e(TAG, "MODULE: onPackageLoaded package=$pkg process=${lp.processName} first=${lp.isFirstPackage}")
        if (!pkg.contains("nfc", ignoreCase = true)) return

        Log.e(TAG, "MODULE: Loaded into process $pkg")
        reportModuleActive(pkg)
        Log.e(TAG, "NFC-HIJACK: Armed in process: $pkg (Thread: ${Thread.currentThread().name})")

        val managerClasses = arrayOf(
            "com.android.nfc.dhimpl.NxpNativeNfcManager",
            "com.android.nfc.dhimpl.StNativeNfcManager",
            "com.android.nfc.dhimpl.NativeNfcManager",
            "com.android.nfc.dhimpl.NativeNfcManager\$NativeNfcManagerExt"
        )

        var managerClassFound = false
        for (className in managerClasses) {
            try {
                val clazz = Class.forName(className, false, cl)
                managerClassFound = true
                Log.e(TAG, "NFC-HIJACK: Found manager class $className")

                clazz.declaredConstructors.forEach { constructor ->
                    hook(constructor).intercept { chain ->
                        val inst = chain.proceed()
                        if (inst != null) {
                            Log.e(TAG, "NFC-HIJACK: Captured manager instance: ${inst.javaClass.name}")
                            synchronized(nativeManagers) {
                                nativeManagers.add(inst)
                            }
                        }
                        inst
                    }
                }

                clazz.declaredMethods.forEach { method ->
                    val name = method.name.lowercase(Locale.ROOT)
                    if (name.contains("sethcetypeaconfig") || name.contains("dosethcetypeaconfig")) {
                        Log.e(TAG, "NFC-HIJACK: Hooking ${clazz.name}.${method.name}(${method.parameterTypes.joinToString { it.simpleName }})")
                        applyHceHijack(method)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "NFC-HIJACK: Manager class unavailable: $className (${t.javaClass.simpleName})")
            }
        }

        if (!managerClassFound) {
            Log.e(TAG, "NFC-HIJACK: No known NativeNfcManager class found in $pkg")
        }

        val serviceClasses = arrayOf(
            "com.android.nfc.NfcService",
            "com.oplus.nfc.OplusNfcService"
        )

        for (className in serviceClasses) {
            try {
                val serviceClass = Class.forName(className, false, cl)
                Log.e(TAG, "NFC-HIJACK: Found service class $className")
                serviceClass.declaredMethods.forEach { method ->
                    val name = method.name.lowercase(Locale.ROOT)
                    if (name.contains("routing") || name.contains("screenstate") || name.contains("applyconfig")) {
                        hook(method).intercept { chain ->
                            lastFetchTime = 0
                            val result = chain.proceed()
                            Log.e(TAG, "NFC-HIJACK: System event ($name) intercepted; re-enforcing state")
                            enforceState()
                            result
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "NFC-HIJACK: Service class unavailable: $className (${t.javaClass.simpleName})")
            }
        }
    }

    private fun reportModuleActive(processName: String) {
        try {
            val app = currentApplication() ?: run {
                Log.w(TAG, "MODULE: currentApplication unavailable; cannot persist load state")
                return
            }
            val bootCount = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
            val values = ContentValues().apply {
                put("module_active", true)
                put("module_process", processName)
                put("module_boot_count", bootCount)
            }
            app.contentResolver.insert(CONFIG_URI, values)
            Log.e(TAG, "MODULE: Load state persisted for $processName, boot=$bootCount")
        } catch (t: Throwable) {
            Log.e(TAG, "MODULE: Failed to persist load state", t)
        }
    }

    private fun enforceState() {
        val config = fetchConfig()
        if (!config.active || config.uid == null) return

        val managers = synchronized(nativeManagers) { nativeManagers.toList() }
        managers.forEach { manager ->
            try {
                manager.javaClass.declaredMethods.forEach { method ->
                    if (method.name.contains("setHceTypeAConfig", ignoreCase = true)) {
                        method.isAccessible = true
                        val params = method.parameterTypes.size
                        if (params < 2 || params > 4) {
                            Log.w(TAG, "NFC-HIJACK: Skip unsupported ${method.name} parameter count=$params")
                            return@forEach
                        }
                        val invokeArgs = arrayOfNulls<Any>(params)
                        invokeArgs[0] = true
                        method.invoke(manager, *invokeArgs)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "NFC-HIJACK: Proactive lock failed for ${manager.javaClass.name}", t)
            }
        }
    }

    private fun applyHceHijack(method: Method) {
        try {
            hook(method).intercept { chain ->
                val config = fetchConfig()
                if (!config.active || config.uid == null) {
                    return@intercept chain.proceed()
                }

                val args = chain.args.toMutableList()
                if (args.size < 2) {
                    Log.e(TAG, "NFC-HIJACK: Unsupported ${method.name}: only ${args.size} args")
                    return@intercept chain.proceed()
                }

                args[0] = true
                args[1] = hexToBytes(config.uid)
                if (args.size >= 3) args[2] = hexToBytes(config.sak ?: "08")
                if (args.size >= 4) args[3] = hexToBytes(config.atqa ?: "0400")

                Log.e(TAG, "NFC-HIJACK: [STRICT LOCK] Forced UID ${config.uid} via ${method.declaringClass.simpleName}.${method.name}")
                chain.proceed(args.toTypedArray())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "NFC-HIJACK: Hook failed: ${method.declaringClass.name}.${method.name}", t)
        }
    }

    data class SimConfig(
        val active: Boolean,
        val uid: String?,
        val sak: String?,
        val atqa: String?
    )

    @SuppressLint("Range")
    private fun fetchConfig(): SimConfig {
        val now = System.currentTimeMillis()
        if (now - lastFetchTime < CACHE_MS && lastConfig != null) return lastConfig!!

        var cursor: Cursor? = null
        try {
            val app = currentApplication()
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
            Log.e(TAG, "NFC-HIJACK: Config read failed", t)
        } finally {
            cursor?.close()
        }
        return lastConfig ?: SimConfig(false, null, null, null)
    }

    private fun currentApplication(): Application? {
        return try {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .invoke(null) as? Application
        } catch (t: Throwable) {
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val s = hex.replace(":", "").replace(" ", "").replace("0x", "", ignoreCase = true)
        require(s.length % 2 == 0) { "Invalid hex length: ${s.length}" }
        return ByteArray(s.length / 2) { index ->
            val offset = index * 2
            s.substring(offset, offset + 2).toInt(16).toByte()
        }
    }
}
