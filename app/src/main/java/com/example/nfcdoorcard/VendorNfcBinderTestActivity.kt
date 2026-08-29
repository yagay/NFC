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
import java.util.concurrent.Executors

/**
 * V10: obtain the OEM Vendor NFC Binder using the exact INfcAdapter Binder transaction
 * reflected from this device: TRANSACTION_getNfcAdapterVendorInterface = 6.
 *
 * This avoids app-side hidden-API reflection of getNfcAdapterVendorInterface(String).
 * No transaction code is guessed.
 */
class VendorNfcBinderTestActivity : ComponentActivity() {
    companion object {
        private const val INFC_DESCRIPTOR = "android.nfc.INfcAdapter"
        private const val TX_GET_VENDOR_INTERFACE = 6
        private const val VENDOR_NAME = "nxp"
        private const val VENDOR_DESCRIPTOR = "com.vendor.nfc.IVendorNfcAdapter"
        private const val TX_ENABLE_SHARE_MODE = 15
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Vendor NFC Binder 测试 V10"

        output = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            text = "V10 不再反射隐藏 getter。\n\n已由设备侧元数据确认：INfcAdapter.getNfcAdapterVendorInterface(String) 的 transaction=6。V10 由 App UID 对主 NFC Binder 发送 transaction 6，参数 vendorName=nxp；只有返回 Binder descriptor 精确匹配 Vendor NFC 接口时，才继续 transaction 15。"
        }

        runButton = Button(this).apply {
            text = "开始 Vendor NFC Binder 测试 V10"
            setOnClickListener { runV10() }
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

    private fun runV10() {
        runButton.isEnabled = false
        output.text = "测试中..."

        executor.execute {
            val report = buildString {
                appendLine("=== VENDOR NFC BINDER TEST V10 ===")
                appendLine("time=${System.currentTimeMillis()}")
                appendLine("app_uid=${Process.myUid()} app_pid=${Process.myPid()} package=$packageName")

                val before = readProviderMap()
                val simulationEnabled = before[ConfigProvider.KEY_SIMULATION_ENABLED].toBoolean()
                val targetUid = before[ConfigProvider.KEY_UID].orEmpty()
                val beforeEvents = before["refresh_probe_events"]?.toIntOrNull() ?: 0
                val reflectedGetTx = before["vendor_infc_stub_transactions"].orEmpty()
                appendLine("simulation_enabled=$simulationEnabled target_uid=$targetUid before_probe_events=$beforeEvents")
                appendLine("main_descriptor=$INFC_DESCRIPTOR get_vendor_tx=$TX_GET_VENDOR_INTERFACE vendor_name=$VENDOR_NAME")
                appendLine("vendor_descriptor=$VENDOR_DESCRIPTOR enable_share_tx=$TX_ENABLE_SHARE_MODE")
                appendLine("metadata_stub_transactions=$reflectedGetTx")

                if (!simulationEnabled || targetUid.isBlank()) {
                    appendLine("RESULT=TEST_NOT_RUN reason=simulation_not_enabled")
                    return@buildString
                }
                if (!reflectedGetTx.contains("TRANSACTION_getNfcAdapterVendorInterface=6")) {
                    appendLine("RESULT=TEST_NOT_RUN reason=device_metadata_does_not_confirm_transaction_6")
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

                val mainBinder = toBinder(service)
                if (mainBinder == null) {
                    appendLine("RESULT=TEST_FAIL stage=MAIN_BINDER reason=cannot_get_asBinder")
                    return@buildString
                }
                val mainDescriptor = runCatching { mainBinder.interfaceDescriptor }.getOrNull().orEmpty()
                appendLine("MAIN_BINDER=${mainBinder.javaClass.name} descriptor=$mainDescriptor alive=${mainBinder.isBinderAlive}")
                if (mainDescriptor != INFC_DESCRIPTOR) {
                    appendLine("RESULT=TEST_FAIL stage=MAIN_BINDER reason=unexpected_descriptor")
                    return@buildString
                }

                val vendorBinder = try {
                    transactGetVendorInterface(mainBinder, this)
                } catch (t: Throwable) {
                    appendLine("GET_VENDOR_ERROR=${describeThrowable(t)}")
                    null
                }
                if (vendorBinder == null) {
                    appendLine("RESULT=TEST_FAIL stage=GET_VENDOR_BINDER reason=null_or_rejected")
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
                        put(ConfigProvider.KEY_RF_STATUS, "WAITING_BINDER_TEST_V10")
                        put(ConfigProvider.KEY_RF_UID, "")
                        put(ConfigProvider.KEY_RF_RESULT, "")
                        put(ConfigProvider.KEY_RF_ERROR, "")
                        put(ConfigProvider.KEY_RF_PID, 0)
                    })
                }.onFailure { appendLine("PROVIDER_RESET_ERROR=${describeThrowable(it)}") }

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

            AppLogger.i("VENDOR_BINDER_TEST_V10:\n$report")
            runCatching { File(cacheDir, "vendor_nfc_binder_test_v10.txt").writeText(report) }
            runOnUiThread {
                output.text = report
                runButton.isEnabled = true
            }
        }
    }

    private fun transactGetVendorInterface(mainBinder: IBinder, log: StringBuilder): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INFC_DESCRIPTOR)
            data.writeString(VENDOR_NAME)
            val ok = mainBinder.transact(TX_GET_VENDOR_INTERFACE, data, reply, 0)
            log.appendLine("GET_VENDOR_TRANSACT code=$TX_GET_VENDOR_INTERFACE vendor=$VENDOR_NAME return=$ok reply_size=${reply.dataSize()}")
            if (!ok) return null
            reply.setDataPosition(0)
            reply.readException()
            val binder = reply.readStrongBinder()
            log.appendLine("GET_VENDOR_READ_STRONG_BINDER=${binder?.javaClass?.name ?: "null"}")
            binder
        } finally {
            reply.recycle()
            data.recycle()
        }
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
