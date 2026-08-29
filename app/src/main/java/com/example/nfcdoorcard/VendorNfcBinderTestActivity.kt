package com.example.nfcdoorcard

import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.File
import java.util.concurrent.Executors

/** V8 focused metadata diagnostic page. */
class VendorNfcBinderTestActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Vendor NFC 元数据诊断 V8"

        output = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            text = "V8 只显示定位 Vendor Binder 入口需要的短字段，避免长报告被截断。\n\n安装后重启手机，再点下方按钮。"
        }

        runButton = Button(this).apply {
            text = "读取 Vendor NFC 元数据 V8"
            setOnClickListener { runV8() }
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

    private fun runV8() {
        runButton.isEnabled = false
        output.text = "读取中..."

        executor.execute {
            val state = readProviderMap()
            val report = buildString {
                appendLine("=== VENDOR NFC METADATA DIAGNOSTIC V8 ===")
                appendLine("time=${System.currentTimeMillis()}")
                appendLine("app_uid=${Process.myUid()} app_pid=${Process.myPid()} package=$packageName")
                appendLine("simulation_enabled=${state[ConfigProvider.KEY_SIMULATION_ENABLED]}")
                appendLine("target_uid=${state[ConfigProvider.KEY_UID]}")
                appendLine("hook_build=${state["hook_build"]}")
                appendLine("hook_pid=${state["hook_pid"]}")
                appendLine("scope_pid=${state["scope_pid"]}")
                appendLine()
                appendLine("vendor_meta_ready=${state["vendor_meta_ready"] ?: "<missing>"}")
                appendLine("vendor_share_mode_transaction=${state["vendor_share_mode_transaction"] ?: "<missing>"}")
                appendLine("vendor_binder_descriptor=${state["vendor_binder_descriptor"] ?: "<missing>"}")
                appendLine()
                appendLine("vendor_getter_signatures=${state["vendor_getter_signatures"] ?: "<missing>"}")
                appendLine("vendor_infc_adapter_methods=${state["vendor_infc_adapter_methods"] ?: "<missing>"}")
                appendLine("vendor_infc_stub_transactions=${state["vendor_infc_stub_transactions"] ?: "<missing>"}")
                appendLine("vendor_nfc_adapter_service_methods=${state["vendor_nfc_adapter_service_methods"] ?: "<missing>"}")
                appendLine("vendor_impl_candidates=${state["vendor_impl_candidates"] ?: "<missing>"}")
                appendLine()

                val focusedPresent = listOf(
                    "vendor_getter_signatures",
                    "vendor_infc_adapter_methods",
                    "vendor_infc_stub_transactions",
                    "vendor_nfc_adapter_service_methods",
                    "vendor_impl_candidates"
                ).any { !state[it].isNullOrBlank() }

                if (state["vendor_meta_ready"] == "true" && focusedPresent) {
                    appendLine("RESULT=FOCUSED_METADATA_READY")
                    appendLine("NEXT=use the focused fields to identify the exact app-visible Vendor Binder acquisition path")
                } else {
                    appendLine("RESULT=FOCUSED_METADATA_NOT_READY")
                    appendLine("NEXT=restart com.android.nfc or reboot so the V8 metadata probe runs")
                }
            }

            AppLogger.i("VENDOR_METADATA_DIAGNOSTIC_V8:\n$report")
            runCatching { File(cacheDir, "vendor_nfc_metadata_v8.txt").writeText(report) }
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
