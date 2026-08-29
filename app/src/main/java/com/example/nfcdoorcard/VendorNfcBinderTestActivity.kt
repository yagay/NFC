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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.Executors

/**
 * One-shot diagnostic for the OxygenOS vendor NFC Binder path.
 *
 * Important: discovery and enableNfcShareMode(true/false) are executed by this app process itself.
 * No su/root command and no com.android.nfc-side relay is used, so Binder/SELinux/permission failures
 * remain visible and the result reflects the app UID's real access.
 */
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
            text = "该测试由 App 自己的 UID 直接访问 Vendor NFC Binder。\n不会调用 root，也不会自动打开分享页。\n\n请先在主界面选择门禁卡并启动模拟，然后点击下方按钮。"
        }
        runButton = Button(this).apply {
            text = "开始一次完整测试"
            setOnClickListener { runOneShotTest() }
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(runButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(output, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        setContentView(ScrollView(this).apply { addView(body) })
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
                appendLine("=== VENDOR NFC BINDER ONE-SHOT TEST ===")
                appendLine("time=${System.currentTimeMillis()}")
                appendLine("app_uid=${Process.myUid()} app_pid=${Process.myPid()} package=$packageName")

                val before = readProviderMap()
                val simulationEnabled = before[ConfigProvider.KEY_SIMULATION_ENABLED].toBoolean()
                val targetUid = before[ConfigProvider.KEY_UID].orEmpty()
                val beforeEvents = before["refresh_probe_events"]?.toIntOrNull() ?: 0
                appendLine("simulation_enabled=$simulationEnabled target_uid=$targetUid before_probe_events=$beforeEvents")

                if (!simulationEnabled || targetUid.isBlank()) {
                    appendLine("RESULT=TEST_NOT_RUN reason=simulation_not_enabled")
                    appendLine("请先回主界面启动目标卡模拟，再运行此测试。")
                    return@buildString
                }

                // Clear only diagnostic RF result fields so a previous success cannot create a false PASS.
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
                    appendLine("service_name=${handle.serviceName ?: "<via NfcAdapter.sService>"}")
                    appendLine("vendor_object=${handle.vendorObject?.javaClass?.name ?: "<binder-only>"}")

                    val enterResult = invokeShareMode(handle, true, this)
                    enterAccepted = enterResult == true
                    appendLine("enableNfcShareMode(true) result=$enterResult")

                    val deadline = System.currentTimeMillis() + 2500L
                    var last = readProviderMap()
                    while (System.currentTimeMillis() < deadline) {
                        last = readProviderMap()
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
                    appendLine("after_true_probe_events=$afterTrueEvents")
                    appendLine("rf_applied_before_cleanup=$rfAppliedBeforeCleanup")
                    val trueState = readProviderMap()
                    appendLine("rf_status_after_true=${trueState[ConfigProvider.KEY_RF_STATUS]}")
                    appendLine("rf_uid_after_true=${trueState[ConfigProvider.KEY_RF_UID]}")
                    appendLine("rf_result_after_true=${trueState[ConfigProvider.KEY_RF_RESULT]}")
                } catch (t: Throwable) {
                    appendLine("CALL_ERROR=${describeThrowable(t)}")
                } finally {
                    if (handle != null) {
                        try {
                            val exitResult = invokeShareMode(handle, false, this)
                            appendLine("enableNfcShareMode(false) cleanup_result=$exitResult")
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
        // Preferred path: obtain the vendor interface exactly as framework NfcAdapter does.
        try {
            val serviceField = NfcAdapter::class.java.getDeclaredField("sService")
            serviceField.isAccessible = true
            val nfcService = serviceField.get(null)
            log.appendLine("DISCOVERY_A:NfcAdapter.sService=${nfcService?.javaClass?.name}")
            if (nfcService != null) {
                val getter = allMethods(nfcService.javaClass).firstOrNull { m ->
                    m.parameterCount == 0 && (
                        m.returnType.name.contains("IVendorNfcAdapter", ignoreCase = true) ||
                            m.name.equals("getVendorNfcAdapter", ignoreCase = true) ||
                            (m.name.contains("vendor", ignoreCase = true) && m.name.contains("nfc", ignoreCase = true) && m.returnType != Void.TYPE)
                        )
                }
                if (getter != null) {
                    getter.isAccessible = true
                    log.appendLine("DISCOVERY_A:getter=${getter.declaringClass.name}#${getter.name} return=${getter.returnType.name}")
                    val vendor = getter.invoke(nfcService)
                    val binder = asBinder(vendor)
                    if (vendor != null && binder != null) {
                        val descriptor = runCatching { binder.interfaceDescriptor }.getOrDefault("")
                        return VendorHandle("NfcAdapter.sService/${getter.name}", descriptor, null, binder, vendor)
                    }
                } else {
                    log.appendLine("DISCOVERY_A:no vendor getter found")
                }
            }
        } catch (t: Throwable) {
            log.appendLine("DISCOVERY_A_ERROR=${describeThrowable(t)}")
        }

        // Fallback path: enumerate ServiceManager from the app UID and match the actual interface descriptor.
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val listMethod = sm.getDeclaredMethod("listServices").apply { isAccessible = true }
            val getMethod = sm.getDeclaredMethod("getService", String::class.java).apply { isAccessible = true }
            val names = listMethod.invoke(null) as? Array<*> ?: emptyArray<Any>()
            log.appendLine("DISCOVERY_B:service_count=${names.size}")
            val ordered = names.mapNotNull { it as? String }.sortedBy { if (it.contains("nfc", true)) 0 else 1 }
            for (name in ordered) {
                val binder = getMethod.invoke(null, name) as? IBinder ?: continue
                val descriptor = runCatching { binder.interfaceDescriptor }.getOrDefault("")
                if (descriptor == "com.vendor.nfc.IVendorNfcAdapter" || descriptor.endsWith(".IVendorNfcAdapter")) {
                    val vendor = asVendorInterface(binder, log)
                    return VendorHandle("ServiceManager", descriptor, name, binder, vendor)
                }
            }
            log.appendLine("DISCOVERY_B:no service with IVendorNfcAdapter descriptor")
        } catch (t: Throwable) {
            log.appendLine("DISCOVERY_B_ERROR=${describeThrowable(t)}")
        }
        return null
    }

    private fun invokeShareMode(handle: VendorHandle, enabled: Boolean, log: StringBuilder): Boolean? {
        val target = handle.vendorObject ?: asVendorInterface(handle.binder, log)
        if (target != null) {
            val method = allMethods(target.javaClass).firstOrNull {
                it.name == "enableNfcShareMode" && it.parameterCount == 1 &&
                    (it.parameterTypes[0] == Boolean::class.javaPrimitiveType || it.parameterTypes[0] == java.lang.Boolean::class.java)
            } ?: runCatching {
                Class.forName("com.vendor.nfc.IVendorNfcAdapter")
                    .getMethod("enableNfcShareMode", Boolean::class.javaPrimitiveType)
            }.getOrNull()
            if (method != null) {
                method.isAccessible = true
                log.appendLine("CALL_PATH=reflection ${method.declaringClass.name}#enableNfcShareMode($enabled)")
                val value = method.invoke(target, enabled)
                return value as? Boolean
            }
        }

        // Last fallback: direct Binder transaction using the generated AIDL Stub transaction constant.
        val stub = Class.forName("com.vendor.nfc.IVendorNfcAdapter\$Stub")
        val field = stub.getDeclaredField("TRANSACTION_enableNfcShareMode").apply { isAccessible = true }
        val code = field.getInt(null)
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
        return runCatching {
            value.javaClass.methods.firstOrNull { it.name == "asBinder" && it.parameterCount == 0 }?.invoke(value) as? IBinder
        }.getOrNull()
    }

    private fun allMethods(clazz: Class<*>): List<Method> =
        (clazz.methods.asList() + clazz.declaredMethods.asList()).distinctBy { it.toGenericString() }

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
