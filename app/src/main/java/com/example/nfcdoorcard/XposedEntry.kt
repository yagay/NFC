package com.example.nfcdoorcard

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import java.util.Locale

class XposedEntry : XposedModule() {

    companion object {
        private const val TAG = "NfcUIDSim"
        private val CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.config/settings")
        private const val CACHE_MS = 2000L
    }

    private var lastConfig: SimConfig? = null
    private var lastFetchTime: Long = 0
    private val nativeManagers = mutableSetOf<Any>()

    init {
        Log.e(TAG, "MODULE: XposedEntry instantiated (Modern API 102)")
    }

    override fun onPackageLoaded(lp: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(lp)
        val pkg = lp.packageName
        val cl = lp.defaultClassLoader

        frameworkLog("MODULE: onPackageLoaded package=$pkg first=${lp.isFirstPackage}")
        if (!pkg.contains("nfc", ignoreCase = true)) return

        frameworkLog("MODULE: Loaded into NFC package $pkg")
        reportModuleActive(pkg)
        frameworkLog("NFC-HIJACK: Armed in package: $pkg (Thread: ${Thread.currentThread().name})")

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
                frameworkLog("NFC-HIJACK: Found manager class $className")

                clazz.declaredConstructors.forEach { constructor ->
                    try {
                        hook(constructor).intercept { chain ->
                            val inst = chain.proceed()
                            if (inst != null) {
                                frameworkLog("NFC-HIJACK: Captured manager instance: ${inst.javaClass.name}")
                                synchronized(nativeManagers) { nativeManagers.add(inst) }
                                reportModuleActive(pkg)
                            }
                            inst
                        }
                    } catch (t: Throwable) {
                        frameworkLog("NFC-HIJACK: Constructor hook failed: $constructor", t)
                    }
                }

                clazz.declaredMethods.forEach { method ->
                    val name = method.name.lowercase(Locale.ROOT)
                    if (name.contains("sethcetypeaconfig") || name.contains("dosethcetypeaconfig")) {
                        frameworkLog("NFC-HIJACK: Candidate ${methodSignature(method)}")
                        applyHceHijack(method)
                    }
                }
            } catch (t: Throwable) {
                frameworkLog("NFC-HIJACK: Manager class unavailable: $className (${t.javaClass.simpleName})")
            }
        }

        if (!managerClassFound) {
            frameworkLog("NFC-HIJACK: No known NativeNfcManager class found in $pkg")
        }

        val serviceClasses = arrayOf(
            "com.android.nfc.NfcService",
            "com.oplus.nfc.OplusNfcService"
        )

        for (className in serviceClasses) {
            try {
                val serviceClass = Class.forName(className, false, cl)
                frameworkLog("NFC-HIJACK: Found service class $className")
                serviceClass.declaredMethods.forEach { method ->
                    val name = method.name.lowercase(Locale.ROOT)
                    if (name.contains("routing") || name.contains("screenstate") || name.contains("applyconfig")) {
                        hook(method).intercept { chain ->
                            lastFetchTime = 0
                            val result = chain.proceed()
                            reportModuleActive(pkg)
                            result
                        }
                    }
                }
            } catch (t: Throwable) {
                frameworkLog("NFC-HIJACK: Service class unavailable: $className (${t.javaClass.simpleName})")
            }
        }
    }

    private fun reportModuleActive(processName: String) {
        try {
            val app = currentApplication() ?: run {
                frameworkLog("MODULE: currentApplication unavailable; heartbeat deferred")
                return
            }
            val bootCount = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
            val values = ContentValues().apply {
                put("module_active", true)
                put("module_process", processName)
                put("module_boot_count", bootCount)
            }
            app.contentResolver.insert(CONFIG_URI, values)
            frameworkLog("MODULE: heartbeat persisted for $processName, boot=$bootCount")
        } catch (t: Throwable) {
            frameworkLog("MODULE: Failed to persist heartbeat", t)
        }
    }

    private fun applyHceHijack(method: Method) {
        val types = method.parameterTypes
        if (!isSupportedSignature(types)) {
            frameworkLog("NFC-HIJACK: SAFE-SKIP unsupported signature ${methodSignature(method)}")
            return
        }

        try {
            hook(method).intercept { chain ->
                val config = fetchConfig()
                if (!config.active || config.uid == null) return@intercept chain.proceed()

                val args = chain.args.toMutableList()
                val uid = hexToBytes(config.uid)
                val sak = (config.sak ?: "08").replace("0x", "", ignoreCase = true).toInt(16)
                val atqa = hexToBytes(config.atqa ?: "0400")

                args[0] = true
                args[1] = uid
                args[2] = when (types[2]) {
                    ByteArray::class.java -> byteArrayOf((sak and 0xFF).toByte())
                    Int::class.javaPrimitiveType, Int::class.javaObjectType -> sak
                    Short::class.javaPrimitiveType, Short::class.javaObjectType -> sak.toShort()
                    Byte::class.javaPrimitiveType, Byte::class.javaObjectType -> sak.toByte()
                    else -> args[2]
                }
                args[3] = when (types[3]) {
                    ByteArray::class.java -> atqa
                    Int::class.javaPrimitiveType, Int::class.javaObjectType -> bytesToInt(atqa)
                    Short::class.javaPrimitiveType, Short::class.javaObjectType -> bytesToInt(atqa).toShort()
                    else -> args[3]
                }

                reportModuleActive(method.declaringClass.name)
                frameworkLog(
                    "NFC-HIJACK: APPLY uid=${config.uid} sak=${config.sak} atqa=${config.atqa} via ${methodSignature(method)}"
                )
                chain.proceed(args.toTypedArray())
            }
        } catch (t: Throwable) {
            frameworkLog("NFC-HIJACK: Hook failed: ${methodSignature(method)}", t)
        }
    }

    private fun isSupportedSignature(types: Array<Class<*>>): Boolean {
        if (types.size != 4) return false
        if (types[0] != Boolean::class.javaPrimitiveType && types[0] != Boolean::class.javaObjectType) return false
        if (types[1] != ByteArray::class.java) return false
        val sakOk = types[2] == ByteArray::class.java ||
            types[2] == Int::class.javaPrimitiveType || types[2] == Int::class.javaObjectType ||
            types[2] == Short::class.javaPrimitiveType || types[2] == Short::class.javaObjectType ||
            types[2] == Byte::class.javaPrimitiveType || types[2] == Byte::class.javaObjectType
        val atqaOk = types[3] == ByteArray::class.java ||
            types[3] == Int::class.javaPrimitiveType || types[3] == Int::class.javaObjectType ||
            types[3] == Short::class.javaPrimitiveType || types[3] == Short::class.javaObjectType
        return sakOk && atqaOk
    }

    private fun methodSignature(method: Method): String {
        return "${method.declaringClass.name}.${method.name}(" +
            method.parameterTypes.joinToString(",") { it.name } + "):${method.returnType.name}"
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
            frameworkLog("NFC-HIJACK: Config read failed", t)
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
        } catch (_: Throwable) {
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

    private fun bytesToInt(bytes: ByteArray): Int {
        var value = 0
        bytes.take(4).forEach { value = (value shl 8) or (it.toInt() and 0xFF) }
        return value
    }

    private fun frameworkLog(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.e(TAG, message)
            try { log(Log.INFO, TAG, message) } catch (_: Throwable) { }
        } else {
            Log.e(TAG, message, throwable)
            try { log(Log.ERROR, TAG, message, throwable) } catch (_: Throwable) { }
        }
    }
}
