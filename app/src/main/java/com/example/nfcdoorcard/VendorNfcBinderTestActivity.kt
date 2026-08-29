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
 * One-shot diagnostic for the OxygenOS vendor NFC Binder path.
 * All Binder discovery is performed by the app UID itself. No root relay is used.
 */
class VendorNfcBinderTestActivity : ComponentActivity() {
    companion object {
        // Native libbinder IBinder::EXTENSION_TRANSACTION = B_PACK_CHARS('_','E','X','T').
        private const val EXTENSION_TRANSACTION = 0x5F455854
        private const val VENDOR_DESCRIPTOR = "com.vendor.nfc.IVendorNfcAdapter"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Vendor NFC 一次性测试"

        output = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            text = "V3 会由 App UID 直接对 NFC 主 Binder 发送只读 _EXT transaction。\n不会调用 root，也不会自动打开分享页。\n\n请先在主界面启动目标卡模拟，再开始测试。"
        }
        runButton = Button(this).apply {
            text = "开始一次完整测试 V3"
            setOnClickListener { runTest() }
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

    private fun runTest() {
        runButton.isEnabled = false
        output.text = "测试中..."
        executor.execute {
            val report = buildString {
                appendLine("=== VENDOR NFC BINDER ONE-SHOT TEST V3 ===")
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
                        put(ConfigProvider.KEY_RF_STATUS, "WAITING_BINDER_TEST_V3")
                        put(ConfigProvider.KEY_RF_UID, "")
                        put(ConfigProvider.KEY_RF_RESULT, "")
                        put(ConfigProvider.KEY_RF_ERROR, "")
                        put(ConfigProvider.KEY_RF_PID, 0)
                    })
                }.onFailure { appendLine("provider_reset_error=${describeThrowable(it)}") }

                val nfcService = try {
                    NfcAdapter::class.java.getDeclaredField("sService").apply { isAccessible = true }.get(null)
                } catch (t: Throwable) {
                    appendLine("DISCOVERY_A_ERROR=${describeThrowable(t)}")
                    null
                }
                appendLine("DISCOVERY_A:NfcAdapter.sService=${nfcService?.javaClass?.name}")

                val mainBinder = asBinder(nfcService)
                if (mainBinder == null) {
                    appendLine("RESULT=TEST_FAIL stage=MAIN_BINDER reason=cannot_obtain_INfcAdapter_binder")
                    return@buildString
                }

                val mainDesc = runCatching { mainBinder.interfaceDescriptor }.getOrNull().orEmpty()
                appendLine("MAIN_BINDER=${mainBinder.javaClass.name} descriptor=$mainDesc")
                appendLine("EXT_TRANSACTION=0x${EXTENSION_TRANSACTION.toString(16).uppercase()}")

                val ext = queryExtension(mainBinder, this)
                if (ext == null) {
                    appendLine("RESULT=TEST_FAIL stage=EXTENSION reason=null_or_transaction_rejected")
                    return@buildString
                }

                val extDesc = runCatching { ext.interfaceDescriptor }.getOrNull().orEmpty()
                appendLine("EXTENSION=SUCCESS class=${ext.javaClass.name} descriptor=$extDesc alive=${ext.isBinderAlive}")

                if (extDesc != VENDOR_DESCRIPTOR && !extDesc.endsWith(".IVendorNfcAdapter")) {
                    appendLine("RESULT=TEST_FAIL stage=EXTENSION_DESCRIPTOR reason=unexpected_descriptor")
                    return@buildString
                }

                appendLine("VENDOR_BINDER=CONFIRMED")

                // The OEM AIDL Stub is loaded inside com.android.nfc, not in the normal app classpath.
                // Do not guess transaction numbers: that could invoke an unrelated vendor operation.
                val txCode = before["vendor_share_mode_transaction"]?.toIntOrNull()
                if (txCode == null || txCode <= 0) {
                    appendLine("TRANSACTION_CODE=NOT_AVAILABLE")
                    appendLine("RESULT=EXTENSION_CONFIRMED_NEED_TX_CODE")
                    appendLine("NEXT=read TRANSACTION_enableNfcShareMode from com.android.nfc classloader and publish diagnostic metadata")
                    return@buildString
                }

                appendLine("TRANSACTION_CODE=$txCode")
                val enterResult = invokeShareMode(ext, extDesc, txCode, true, this)
                appendLine("enableNfcShareMode(true) result=$enterResult")

                var rfApplied = false
                var afterEvents = beforeEvents
                val deadline = System.currentTimeMillis() + 3000L
                while (System.currentTimeMillis() < deadline) {
                    val state = readProviderMap()
                    afterEvents = state["refresh_probe_events"]?.toIntOrNull() ?: 0
                    if (afterEvents > beforeEvents &&
                        state[ConfigProvider.KEY_RF_STATUS] == "RF_UID_APPLIED" &&
                        state[ConfigProvider.KEY_RF_UID].equals(targetUid, true) &&
                        state[ConfigProvider.KEY_RF_RESULT] == "0") {
                        rfApplied = true
                        break
                    }
                    Thread.sleep(50)
                }
                appendLine("after_true_probe_events=$afterEvents")
                appendLine("rf_applied_before_cleanup=$rfApplied")

                try {
                    appendLine("enableNfcShareMode(false) cleanup_result=${invokeShareMode(ext, extDesc, txCode, false, this)}")
                } catch (t: Throwable) {
                    appendLine("CLEANUP_ERROR=${describeThrowable(t)}")
                }

                appendLine("RESULT=${if (enterResult == true && rfApplied) "TEST_PASS" else "TEST_FAIL_CALL_OR_RF"}")
            }

            AppLogger.i("VENDOR_BINDER_TEST_V3:\n$report")
            runCatching { File(cacheDir, "vendor_nfc_binder_test.txt").writeText(report) }
            runOnUiThread {
                output.text = report
                runButton.isEnabled = true
            }
        }
    }

    private fun queryExtension(mainBinder: IBinder, log: StringBuilder): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            val ok = mainBinder.transact(EXTENSION_TRANSACTION, data, reply, 0)
            log.appendLine("EXT_TRANSACT_RETURN=$ok reply_size=${reply.dataSize()}")
            if (!ok) return null
            reply.setDataPosition(0)
            val ext = reply.readStrongBinder()
            log.appendLine("EXT_READ_STRONG_BINDER=${ext?.javaClass?.name ?: "null"}")
            ext
        } catch (t: Throwable) {
            log.appendLine("EXT_ERROR=${describeThrowable(t)}")
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun invokeShareMode(binder: IBinder, descriptor: String, code: Int, enabled: Boolean, log: StringBuilder): Boolean? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor.ifBlank { VENDOR_DESCRIPTOR })
            data.writeBoolean(enabled)
            val ok = binder.transact(code, data, reply, 0)
            log.appendLine("VENDOR_TRANSACT enabled=$enabled code=$code return=$ok reply_size=${reply.dataSize()}")
            if (!ok) return false
            reply.setDataPosition(0)
            reply.readException()
            if (reply.dataAvail() >= 4) reply.readBoolean() else true
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun asBinder(value: Any?): IBinder? {
        if (value == null) return null
        if (value is IBinder) return value
        return try {
            val m = value.javaClass.methods.firstOrNull { it.name == "asBinder" && it.parameterCount == 0 }
                ?: value.javaClass.declaredMethods.firstOrNull { it.name == "asBinder" && it.parameterCount == 0 }
                ?: return null
            m.isAccessible = true
            m.invoke(value) as? IBinder
        } catch (_: Throwable) {
            null
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
