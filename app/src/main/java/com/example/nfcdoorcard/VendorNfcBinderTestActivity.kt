package com.example.nfcdoorcard

import android.content.ContentValues
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.Executors

/** One-shot diagnostic for the OxygenOS vendor NFC Binder path. */
class VendorNfcBinderTestActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Vendor NFC 一次性测试"
        output = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            text = "由 App 自己的 UID 直接探测 Vendor NFC Binder。\n不会调用 root，也不会自动打开分享页。\n\n请先在主界面启动目标卡模拟，再点下方按钮。"
        }
        runButton = Button(this).apply {
            text = "开始一次完整测试"
            setOnClickListener { runOneShotTest() }
        }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@VendorNfcBinderTestActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                addView(runButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                addView(output, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        })
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun runOneShotTest() {
        runButton.isEnabled = false
        output.text = "测试中..."
        executor.execute {
            val report = buildString {
                appendLine("=== VENDOR NFC BINDER ONE-SHOT TEST V2 ===")
                appendLine("time=${System.currentTimeMillis()}")
                appendLine("app_uid=${Process.myUid()} app_pid=${Process.myPid()} package=$packageName")

                val before = readProviderMap()
                val simulationEnabled = before[ConfigProvider.KEY_SIMULATION_ENABLED].toBoolean()
                val targetUid = before[ConfigProvider.KEY_UID].orEmpty()
                val beforeEvents = before["refresh_probe_events"]?.toIntOrNull() ?: 0
                appendLine("simulation_enabled=$simulationEnabled target_uid=$targetUid before_probe_events=$beforeEvents")
                if (!simulationEnabled || targetUid.isBlank()) {
                    appendLine("RESULT=TEST_NOT_RUN reason=simulation_not_enabled")
                    return@buildString
                }

                runCatching {
                    contentResolver.insert(ConfigProvider.URI, ContentValues().apply {
                        put(ConfigProvider.KEY_RF_STATUS, "WAITING_BINDER_TEST")
                        put(ConfigProvider.KEY_RF_UID, "")
                        put(ConfigProvider.KEY_RF_RESULT, "")
                        put(ConfigProvider.KEY_RF_ERROR, "")
                        put(ConfigProvider.KEY_RF_PID, 0)
                    })
                }.onFailure { appendLine("provider_reset_error=${describeThrowable(it)}") }

                var handle: VendorHandle? = null
                var enterAccepted = false
                var rfAppliedBeforeCleanup = false
                var afterTrueEvents = beforeEvents
                try {
                    handle = discoverVendorHandle(this)
                    if (handle == null) {
                        appendLine("RESULT=TEST_FAIL stage=DISCOVERY reason=vendor_binder_not_found_or_not_accessible")
                        return@buildString
                    }
                    appendLine("DISCOVERY=SUCCESS path=${handle.path}")
                    appendLine("descriptor=${handle.descriptor}")
                    appendLine("service_name=${handle.serviceName ?: "<nested/interface>"}")
                    appendLine("vendor_object=${handle.vendorObject?.javaClass?.name ?: "<binder-only>"}")

                    val enterResult = invokeShareMode(handle, true, this)
                    enterAccepted = enterResult == true
                    appendLine("enableNfcShareMode(true) result=$enterResult")

                    val deadline = System.currentTimeMillis() + 3000L
                    while (System.currentTimeMillis() < deadline) {
                        val last = readProviderMap()
                        afterTrueEvents = last["refresh_probe_events"]?.toIntOrNull() ?: 0
                        val rfStatus = last[ConfigProvider.KEY_RF_STATUS].orEmpty()
                        val rfUid = last[ConfigProvider.KEY_RF_UID].orEmpty()
                        val rfResult = last[ConfigProvider.KEY_RF_RESULT].orEmpty()
                        if (afterTrueEvents > beforeEvents && rfStatus == "RF_UID_APPLIED" && rfUid.equals(targetUid, true) && rfResult == "0") {
                            rfAppliedBeforeCleanup = true
                            break
                        }
                        Thread.sleep(50)
                    }
                    val trueState = readProviderMap()
                    appendLine("after_true_probe_events=$afterTrueEvents")
                    appendLine("rf_applied_before_cleanup=$rfAppliedBeforeCleanup")
                    appendLine("rf_status_after_true=${trueState[ConfigProvider.KEY_RF_STATUS]}")
                    appendLine("rf_uid_after_true=${trueState[ConfigProvider.KEY_RF_UID]}")
                    appendLine("rf_result_after_true=${trueState[ConfigProvider.KEY_RF_RESULT]}")
                } catch (t: Throwable) {
                    appendLine("CALL_ERROR=${describeThrowable(t)}")
                } finally {
                    if (handle != null) {
                        try {
                            appendLine("enableNfcShareMode(false) cleanup_result=${invokeShareMode(handle, false, this)}")
                        } catch (t: Throwable) {
                            appendLine("CLEANUP_ERROR=${describeThrowable(t)}")
                        }
                    }
                }

                val finalResult = when {
                    enterAccepted && rfAppliedBeforeCleanup -> "TEST_PASS"
                    !enterAccepted -> "TEST_FAIL_CALL_NOT_ACCEPTED"
                    else -> "TEST_FAIL_NO_RF_APPLY"
                }
                appendLine("RESULT=$finalResult")
                appendLine("PASS_CRITERIA=app_uid_direct_call_accepted AND new_probe_event AND RF_UID_APPLIED_before_false_cleanup")
            }

            AppLogger.i("VENDOR_BINDER_TEST:\n$report")
            runCatching { File(cacheDir, "vendor_nfc_binder_test.txt").writeText(report) }
            runOnUiThread {
                output.text = report
                runButton.isEnabled = true
            }
        }
    }

    private data class VendorHandle(
        val path: String,
        val descriptor: String,
        val serviceName: String?,
        val binder: IBinder,
        val vendorObject: Any?
    )

    private fun discoverVendorHandle(log: StringBuilder): VendorHandle? {
        val nfcService = try {
            NfcAdapter::class.java.getDeclaredField("sService").apply { isAccessible = true }.get(null)
        } catch (t: Throwable) {
            log.appendLine("DISCOVERY_A_ERROR=${describeThrowable(t)}")
            null
        }
        log.appendLine("DISCOVERY_A:NfcAdapter.sService=${nfcService?.javaClass?.name}")

        // A1: the standard INfcAdapter proxy itself may expose a vendor getter on OEM builds.
        if (nfcService != null) {
            inspectMethods("DISCOVERY_A_METHOD", nfcService, log)
            for (m in allMethods(nfcService.javaClass)) {
                if (m.parameterCount != 0) continue
                val interesting = m.name.contains("vendor", true) || m.name.contains("oplus", true) ||
                    m.name.contains("extension", true) || m.returnType.name.contains("VendorNfc", true)
                if (!interesting || m.returnType == Void.TYPE) continue
                try {
                    m.isAccessible = true
                    val value = m.invoke(nfcService)
                    val h = handleFromValue("INfcAdapter#${m.name}", value, log)
                    if (h != null) return h
                } catch (t: Throwable) {
                    log.appendLine("DISCOVERY_A_CALL ${m.name} ERROR=${describeThrowable(t)}")
                }
            }
        }

        // A2: inspect the backing INfcAdapter binder and its Binder extension.
        val mainBinder = asBinder(nfcService)
        if (mainBinder != null) {
            val mainDesc = runCatching { mainBinder.interfaceDescriptor }.getOrNull().orEmpty()
            log.appendLine("DISCOVERY_EXT:main_binder=${mainBinder.javaClass.name} descriptor=$mainDesc")
            try {
                val getExtension = findNoArgMethod(mainBinder.javaClass, "getExtension")
                    ?: findNoArgMethod(IBinder::class.java, "getExtension")
                if (getExtension == null) {
                    log.appendLine("DISCOVERY_EXT:getExtension method not found")
                } else {
                    getExtension.isAccessible = true
                    val ext = getExtension.invoke(mainBinder) as? IBinder
                    if (ext == null) {
                        log.appendLine("DISCOVERY_EXT:extension=null")
                    } else {
                        val desc = runCatching { ext.interfaceDescriptor }.getOrNull().orEmpty()
                        log.appendLine("DISCOVERY_EXT:extension=${ext.javaClass.name} descriptor=$desc")
                        if (isVendorDescriptor(desc)) {
                            return VendorHandle("INfcAdapter.asBinder/getExtension", desc, null, ext, asVendorInterface(ext, log))
                        }
                        // Some OEMs return a generic extension wrapper; inspect its local interface/object too.
                        val local = runCatching { ext.queryLocalInterface(desc) }.getOrNull()
                        val h = handleFromValue("INfcAdapter.getExtension/local", local, log)
                        if (h != null) return h
                    }
                }
            } catch (t: Throwable) {
                log.appendLine("DISCOVERY_EXT_ERROR=${describeThrowable(t)}")
            }
        } else {
            log.appendLine("DISCOVERY_EXT:could_not_obtain_main_binder")
        }

        // B: inspect static NfcAdapter fields/methods for OEM extension objects.
        inspectClass("android.nfc.NfcAdapter", log)?.let { h -> return h }

        // C: try likely framework wrapper classes available on OxygenOS; only no-arg/static discovery is attempted.
        val candidates = listOf(
            "android.nfc.OplusNfcAdapter",
            "android.nfc.OplusNfcManager",
            "com.oplus.nfc.OplusNfcAdapter",
            "com.oplus.nfc.VendorNfcAdapter",
            "com.vendor.nfc.VendorNfcAdapter",
            "com.vendor.nfc.IVendorNfcAdapter"
        )
        for (name in candidates) {
            try {
                val clazz = Class.forName(name)
                log.appendLine("DISCOVERY_CLASS:FOUND $name")
                inspectClassObject(clazz, log)?.let { return it }
            } catch (t: Throwable) {
                log.appendLine("DISCOVERY_CLASS:MISS $name ${t.javaClass.simpleName}")
            }
        }

        // D: independent ServiceManager service, kept as a fallback.
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val listMethod = sm.getDeclaredMethod("listServices").apply { isAccessible = true }
            val getMethod = sm.getDeclaredMethod("getService", String::class.java).apply { isAccessible = true }
            val names = listMethod.invoke(null) as? Array<*> ?: emptyArray<Any>()
            log.appendLine("DISCOVERY_SM:service_count=${names.size}")
            val ordered = names.mapNotNull { it as? String }.sortedBy { if (it.contains("nfc", true)) 0 else 1 }
            for (name in ordered) {
                val binder = getMethod.invoke(null, name) as? IBinder ?: continue
                val desc = runCatching { binder.interfaceDescriptor }.getOrNull().orEmpty()
                if (name.contains("nfc", true)) log.appendLine("DISCOVERY_SM:nfc_service=$name descriptor=$desc")
                if (isVendorDescriptor(desc)) {
                    return VendorHandle("ServiceManager", desc, name, binder, asVendorInterface(binder, log))
                }
            }
            log.appendLine("DISCOVERY_SM:no IVendorNfcAdapter descriptor")
        } catch (t: Throwable) {
            log.appendLine("DISCOVERY_SM_ERROR=${describeThrowable(t)}")
        }
        return null
    }

    private fun inspectClass(name: String, log: StringBuilder): VendorHandle? = try {
        inspectClassObject(Class.forName(name), log)
    } catch (t: Throwable) {
        log.appendLine("DISCOVERY_STATIC_ERROR $name=${describeThrowable(t)}")
        null
    }

    private fun inspectClassObject(clazz: Class<*>, log: StringBuilder): VendorHandle? {
        for (f in allFields(clazz)) {
            if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            val interesting = f.name.contains("vendor", true) || f.name.contains("oplus", true) ||
                f.name.contains("service", true) || f.type.name.contains("VendorNfc", true)
            if (!interesting) continue
            try {
                f.isAccessible = true
                val v = f.get(null)
                log.appendLine("DISCOVERY_FIELD:${clazz.name}#${f.name} type=${f.type.name} value=${v?.javaClass?.name}")
                handleFromValue("${clazz.name}#${f.name}", v, log)?.let { return it }
            } catch (t: Throwable) {
                log.appendLine("DISCOVERY_FIELD_ERROR:${clazz.name}#${f.name} ${describeThrowable(t)}")
            }
        }
        for (m in allMethods(clazz)) {
            if (!java.lang.reflect.Modifier.isStatic(m.modifiers) || m.parameterCount != 0 || m.returnType == Void.TYPE) continue
            val interesting = m.name.contains("vendor", true) || m.name.contains("oplus", true) ||
                m.name.contains("service", true) || m.name.contains("extension", true) ||
                m.returnType.name.contains("VendorNfc", true)
            if (!interesting) continue
            try {
                m.isAccessible = true
                val v = m.invoke(null)
                log.appendLine("DISCOVERY_STATIC_METHOD:${clazz.name}#${m.name} return=${m.returnType.name} value=${v?.javaClass?.name}")
                handleFromValue("${clazz.name}#${m.name}", v, log)?.let { return it }
            } catch (t: Throwable) {
                log.appendLine("DISCOVERY_STATIC_METHOD_ERROR:${clazz.name}#${m.name} ${describeThrowable(t)}")
            }
        }
        return null
    }

    private fun inspectMethods(prefix: String, value: Any, log: StringBuilder) {
        allMethods(value.javaClass)
            .filter { it.name.contains("vendor", true) || it.name.contains("oplus", true) || it.name.contains("extension", true) }
            .take(40)
            .forEach { log.appendLine("$prefix:${it.name}(${it.parameterCount}) -> ${it.returnType.name}") }
    }

    private fun handleFromValue(path: String, value: Any?, log: StringBuilder): VendorHandle? {
        if (value == null) return null
        val binder = asBinder(value) ?: return null
        val desc = runCatching { binder.interfaceDescriptor }.getOrNull().orEmpty()
        log.appendLine("DISCOVERY_VALUE:path=$path value=${value.javaClass.name} binder=${binder.javaClass.name} descriptor=$desc")
        if (isVendorDescriptor(desc) || value.javaClass.name.contains("IVendorNfcAdapter", true)) {
            val vendor = if (value is IBinder) asVendorInterface(binder, log) else value
            return VendorHandle(path, desc.ifBlank { "com.vendor.nfc.IVendorNfcAdapter" }, null, binder, vendor)
        }
        return null
    }

    private fun isVendorDescriptor(desc: String): Boolean =
        desc == "com.vendor.nfc.IVendorNfcAdapter" || desc.endsWith(".IVendorNfcAdapter")

    private fun invokeShareMode(handle: VendorHandle, enabled: Boolean, log: StringBuilder): Boolean? {
        val target = handle.vendorObject ?: asVendorInterface(handle.binder, log)
        if (target != null) {
            val method = allMethods(target.javaClass).firstOrNull {
                it.name == "enableNfcShareMode" && it.parameterCount == 1 &&
                    (it.parameterTypes[0] == Boolean::class.javaPrimitiveType || it.parameterTypes[0] == java.lang.Boolean::class.java)
            } ?: runCatching {
                Class.forName("com.vendor.nfc.IVendorNfcAdapter").getMethod("enableNfcShareMode", Boolean::class.javaPrimitiveType)
            }.getOrNull()
            if (method != null) {
                method.isAccessible = true
                log.appendLine("CALL_PATH=reflection ${method.declaringClass.name}#enableNfcShareMode($enabled)")
                return method.invoke(target, enabled) as? Boolean
            }
        }

        val stub = Class.forName("com.vendor.nfc.IVendorNfcAdapter\$Stub")
        val code = stub.getDeclaredField("TRANSACTION_enableNfcShareMode").apply { isAccessible = true }.getInt(null)
        log.appendLine("CALL_PATH=direct_transact code=$code enabled=$enabled")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(handle.descriptor.ifBlank { "com.vendor.nfc.IVendorNfcAdapter" })
            data.writeBoolean(enabled)
            val transacted = handle.binder.transact(code, data, reply, 0)
            log.appendLine("transact_return=$transacted")
            if (!transacted) return false
            reply.readException()
            reply.readBoolean()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun asVendorInterface(binder: IBinder, log: StringBuilder): Any? = try {
        val stub = Class.forName("com.vendor.nfc.IVendorNfcAdapter\$Stub")
        val method = stub.getDeclaredMethod("asInterface", IBinder::class.java).apply { isAccessible = true }
        method.invoke(null, binder)
    } catch (t: Throwable) {
        log.appendLine("asVendorInterface_error=${describeThrowable(t)}")
        null
    }

    private fun asBinder(value: Any?): IBinder? {
        if (value == null) return null
        if (value is IBinder) return value
        return try {
            val method = findNoArgMethod(value.javaClass, "asBinder") ?: return null
            method.isAccessible = true
            method.invoke(value) as? IBinder
        } catch (_: Throwable) { null }
    }

    private fun findNoArgMethod(clazz: Class<*>, name: String): Method? =
        allMethods(clazz).firstOrNull { it.name == name && it.parameterCount == 0 }

    private fun allMethods(clazz: Class<*>): List<Method> =
        (clazz.methods.asList() + clazz.declaredMethods.asList()).distinctBy { it.toGenericString() }

    private fun allFields(clazz: Class<*>): List<Field> =
        (clazz.fields.asList() + clazz.declaredFields.asList()).distinctBy { "${it.declaringClass.name}#${it.name}" }

    private fun readProviderMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        runCatching {
            contentResolver.query(ConfigProvider.URI, null, null, null, null)?.use { c ->
                while (c.moveToNext()) map[c.getString(0)] = c.getString(1)
            }
        }
        return map
    }

    private fun describeThrowable(input: Throwable): String {
        var t = input
        if (t is InvocationTargetException && t.targetException != null) t = t.targetException
        return buildString {
            append(t.javaClass.name)
            append(": ")
            append(t.message ?: "<no message>")
            t.cause?.takeIf { it !== t }?.let { append(" | cause=${it.javaClass.name}: ${it.message}") }
        }
    }
}
