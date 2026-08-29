package com.example.nfcdoorcard

import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.File
import java.util.concurrent.Executors

/** Validates the reusable production VendorNfcController against the proven V12 flow. */
class VendorNfcBinderTestActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val controller = VendorNfcController()
    private lateinit var output: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Vendor NFC 正式控制器验证 V13"
        NfcAdapter.getDefaultAdapter(this)

        output = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            text = "V13 不再复制 Binder 实现；测试页直接调用正式 VendorNfcController。\n\n验证：transaction 6 + vendor → descriptor 校验 → transaction 15 → RF_UID_APPLIED → false 清理。"
        }
        runButton = Button(this).apply {
            text = "验证正式 Vendor NFC 控制器 V13"
            setOnClickListener { runV13() }
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

    private fun runV13() {
        runButton.isEnabled = false
        output.text = "测试中..."
        executor.execute {
            val report = buildString {
                appendLine("=== VENDOR NFC PRODUCTION CONTROLLER TEST V13 ===")
                appendLine("time=${System.currentTimeMillis()}")
                appendLine("app_uid=${Process.myUid()} app_pid=${Process.myPid()} package=$packageName")

                val before = readProviderMap()
                val enabled = before[ConfigProvider.KEY_SIMULATION_ENABLED].toBoolean()
                val uid = before[ConfigProvider.KEY_UID].orEmpty()
                val beforeEvents = before["refresh_probe_events"]?.toIntOrNull() ?: 0
                appendLine("simulation_enabled=$enabled target_uid=$uid before_probe_events=$beforeEvents")
                if (!enabled || uid.isBlank()) {
                    appendLine("RESULT=TEST_NOT_RUN reason=simulation_not_enabled")
                    return@buildString
                }

                val enter = controller.setShareMode(true)
                appendLine("ENTER success=${enter.success} stage=${enter.stage} detail=${enter.detail} descriptor=${enter.vendorDescriptor}")

                var rfApplied = false
                var afterEvents = beforeEvents
                if (enter.success) {
                    val deadline = System.currentTimeMillis() + 3000L
                    while (System.currentTimeMillis() < deadline) {
                        val state = readProviderMap()
                        afterEvents = state["refresh_probe_events"]?.toIntOrNull() ?: 0
                        if (afterEvents > beforeEvents &&
                            state[ConfigProvider.KEY_RF_STATUS] == "RF_UID_APPLIED" &&
                            state[ConfigProvider.KEY_RF_UID].equals(uid, true) &&
                            state[ConfigProvider.KEY_RF_RESULT] == "0") {
                            rfApplied = true
                            break
                        }
                        Thread.sleep(50)
                    }
                }

                val exit = controller.setShareMode(false)
                appendLine("EXIT success=${exit.success} stage=${exit.stage} detail=${exit.detail} descriptor=${exit.vendorDescriptor}")
                appendLine("after_true_probe_events=$afterEvents")
                appendLine("rf_applied_before_cleanup=$rfApplied")
                appendLine("RESULT=${if (enter.success && rfApplied && exit.success) "TEST_PASS" else "TEST_FAIL"}")
            }

            AppLogger.i("VENDOR_PRODUCTION_CONTROLLER_TEST_V13:\n$report")
            runCatching { File(cacheDir, "vendor_nfc_controller_v13.txt").writeText(report) }
            runOnUiThread {
                output.text = report
                runButton.isEnabled = true
            }
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
}
