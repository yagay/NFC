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

/** V9: obtain the OEM Vendor NFC Binder through the app-visible INfcAdapter API. */
class VendorNfcBinderTestActivity : ComponentActivity() {
    companion object {
        private const val VENDOR_NAME = "nxp"
        private const val VENDOR_DESCRIPTOR = "com.vendor.nfc.IVendorNfcAdapter"
        private const val TX_ENABLE_SHARE_MODE = 15
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Vendor NFC 直接测试 V9"

        output = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            text = "V9 使用已经确认的系统接口 INfcAdapter.getNfcAdapterVendorInterface(String)。\n当前设备实现为 NxpNfcService，因此传入 vendorName=nxp。\n只有返回 Binder descriptor 精确等于 com.vendor.nfc.IVendorNfcAdapter 时才执行 transaction 15。"
        }

        runButton = Button(this).apply {
            text = "开始 Vendor NFC 直接测试 V9"
            setOnClickListener { runV9() }
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

    private fun runV9() {
        runButton.isEnabled = false
        output.text = "测试中..."

        executor.execute {
            val report = buildString {
                appendLine("=== VENDOR NFC DIRECT TEST V9 ===")
                appendLine("time=${System.currentTimeMillis()}")
                appendLine("app_uid=${Process.myUid()} app_pid=${Process.myPid()} package=$packageName")

                val before = readProviderMap()
                val simulationEnabled = before[ConfigProvider.KEY_SIMULATION_ENABLED].toBoolean()
                val targetUid = before[ConfigProvider.KEY_UID].orEmpty()
                val beforeEvents = before["refresh_probe_events"]?.toIntOrNull() ?: 0
                appendLine("simulation_enabled=$simulationEnabled target_uid=$targetUid before_probe_events=$beforeEvents")
                appendLine("vendor_name=$VENDOR_NAME tx_enable_share_mode=$TX_ENABLE_SHARE_MODE descriptor=$VENDOR_DESCRIPTOR")

                if (!simulationEnabled || targetUid.isBlank()) {
                    appendLine("RESULT=TEST_NOT_RUN reason=simulation_not_enabled")
                    return@buildString
                }

                runCatching { NfcAdapter.getDefaultAdapter(this@VendorNfcBinderTestActivity) }
                    .onSuccess { appendLine("NFC_ADAPTER_INIT=${it?.javaClass?.name ?: "null"}") }
                    .onFailure { appendLine("NFC_ADAPTER_INIT_ERROR=${describeThrowable(it)}") }

                val service = try {
                    NfcAdapter::class.java.getDeclaredField("sService").apply { isAccessible = true }.get(null)
                } catch (t: Throwable) {
                    appendLine("INFC_SERVICE_ERROR=${describeThrowable(t)}")
                    null
                }
                appendLine("INFC_SERVICE=${service?.javaClass?.name ?: "null"}")
                if (service == null) {
                    appendLine("RESULT=TEST_FAIL stage=INFC_SERVICE reason=null")
                    return@buildString
                }

                val getter = collectMethods(service.javaClass)
                    .firstOrNull {
                        it.name == "getNfcAdapterVendorInterface" &&
                            it.parameterCount == 1 &&
                            it.parameterTypes[0] == String::class.java
                    }
                appendLine("GETTER=${getter?.toGenericString() ?: "<missing>"}")
                if (getter == null) {
                    appendLine("RESULT=TEST_FAIL stage=VENDOR_GETTER reason=String_getter_not_exposed")
                    return@buildString
                }

                val vendorBinder = try {
                    getter.isAccessible = true
                    val returned = getter.invoke(service, VENDOR_NAME)
                    appendLine("GETTER_RETURN_CLASS=${returned?.javaClass?.name ?: "null"}")
                    toBinder(returned)
                } catch (t: Throwable) {
                    appendLine("GETTER_CALL_ERROR=${describeThrowable(t)}")
                    null
                }

                if (vendorBinder == null) {
                    appendLine("RESULT=TEST_FAIL stage=VENDOR_GETTER reason=null_or_call_rejected")
                    return@buildString
                }

                val descriptor = runCatching { vendorBinder.interfaceDescriptor }.getOrNull().orEmpty()
                appendLine("VENDOR_BINDER=${vendorBinder.javaClass.name} descriptor=$descriptor alive=${vendorBinder.isBinderAlive}")
                if (descriptor != VENDOR_DESCRIPTOR) {
                    appendLine("RESULT=TEST_FAIL stage=VENDOR_DESCRIPTOR reason=unexpected_descriptor")
                    return@buildString
                }

                runCatching {
                    contentResolver.insert(ConfigProvider.URI, ContentValues().apply {
                        put(ConfigProvider.KEY_RF_STATUS, "WAITING_BINDER_TEST_V9")
                        put(ConfigProvider.KEY_RF_UID, "")
                        put(ConfigProvider.KEY_RF_RESULT, "")
                        put(ConfigProvider.KEY_RF_ERROR, "")
                        put(ConfigProvider.KEY_RF_PID, 0)
                    })
                }

                var enterAccepted: Boolean? = null
                var rfApplied = false
                var afterEvents = beforeEvents
                try {
                    enterAccepted = transactShareMode(vendorBinder, true, this)
                    appendLine("enableNfcShareMode(true)=$enterAccepted")

                    val deadline = System.currentTimeMillis() + 3000L
                    while (System.currentTimeMillis() < deadline) {
                        val state = readProviderMap()
                        afterEvents = state["refresh_probe_events"]?.toIntOrNull() ?: 0
                        if (afterEvents > beforeEvents &&
                            state[ConfigProvider.KEY_RF_STATUS] == "RF_UID_APPLIED" &&
                            state[ConfigProvider.KEY_RF_UID].equals(targetUid, ignoreCase = true) &&
                            state[ConfigProvider.KEY_RF_RESULT] == "0") {
                            rfApplied = true
                            break
                        }
                        Thread.sleep(50)
                    }
                } catch (t: Throwable) {
                    appendLine("CALL_ERROR=${describeThrowable(t)}")
                } finally {
                    try {
                        appendLine("enableNfcShareMode(false)_cleanup=${transactShareMode(vendorBinder, false, this)}")
                    } catch (t: Throwable) {
                        appendLine("CLEANUP_ERROR=${describeThrowable(t)}")
                    }
                }

                appendLine("after_true_probe_events=$afterEvents")
                appendLine("rf_applied_before_cleanup=$rfApplied")
                appendLine("RESULT=${if (enterAccepted == true && rfApplied) "TEST_PASS" else "TEST_FAIL_CALL_OR_RF"}")
            }

            AppLogger.i("VENDOR_DIRECT_TEST_V9:\n$report")
            runCatching { File(cacheDir, "vendor_nfc_direct_test_v9.txt").writeText(report) }
            runOnUiThread {
                output.text = report
                runButton.isEnabled = true
            }
        }
    }

    private fun collectMethods(type: Class<*>): List<Method> {
        val out = ArrayList<Method>()
        var c: Class<*>? = type
        while (c != null) {
            runCatching { out.addAll(c.declaredMethods) }
            c.interfaces.forEach { i -> runCatching { out.addAll(i.methods) } }
            c = c.superclass
        }
        runCatching { out.addAll(type.methods) }
        return out.distinctBy { it.toGenericString() }
    }

    private fun toBinder(value: Any?): IBinder? {
        if (value == null) return null
        if (value is IBinder) return value
        return try {
            val method = value.javaClass.methods.firstOrNull { it.name == "asBinder" && it.parameterCount == 0 }
                ?: value.javaClass.declaredMethods.firstOrNull { it.name == "asBinder" && it.parameterCount == 0 }
                ?: return null
            method.isAccessible = true
            method.invoke(value) as? IBinder
        } catch (_: Throwable) {
            null
        }
    }

    private fun transactShareMode(binder: IBinder, enabled: Boolean, log: StringBuilder): Boolean? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(VENDOR_DESCRIPTOR)
            data.writeBoolean(enabled)
            val ok = binder.transact(TX_ENABLE_SHARE_MODE, data, reply, 0)
            log.appendLine("VENDOR_TRANSACT enabled=$enabled code=$TX_ENABLE_SHARE_MODE return=$ok reply_size=${reply.dataSize()}")
            if (!ok) return false
            reply.setDataPosition(0)
            reply.readException()
            if (reply.dataAvail() >= 4) reply.readBoolean() else true
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun readProviderMap(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        runCatching {
            contentResolver.query(ConfigProvider.URI, null, null, null, null)?.use { c ->
                while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
            }
        }
        return out
    }

    private fun describeThrowable(input: Throwable): String {
        var t = input
        if (t is InvocationTargetException && t.targetException != null) t = t.targetException
        return buildString {
            append(t.javaClass.name).append(": ").append(t.message ?: "<no message>")
            t.cause?.takeIf { it !== t }?.let { append(" | cause=${it.javaClass.name}: ${it.message}") }
        }
    }
}
