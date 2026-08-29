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

/**
 * V7 metadata diagnostic page.
 *
 * The com.android.nfc-side probe already discovered the exact Vendor Binder transaction
 * metadata. This page only reads and displays that metadata, including the full getter
 * signatures discovered in VendorNfcService / NfcAdapterService / INfcAdapter classes.
 */
class VendorNfcBinderTestActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Vendor NFC 元数据诊断 V7"

        output = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            text = "V7 不再重复执行已知失败的零参数 getter 测试。\n\n它只读取 com.android.nfc 侧最新元数据扫描结果，重点显示 getNfcAdapterVendorInterface 的完整参数类型和 INfcAdapter/NfcAdapterService 的 Vendor Binder 暴露路径。"
        }

        runButton = Button(this).apply {
            text = "读取 Vendor NFC 元数据 V7"
            setOnClickListener { runV7() }
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

    private fun runV7() {
        runButton.isEnabled = false
        output.text = "读取中..."

        executor.execute {
            val state = readProviderMap()
            val report = buildString {
                appendLine("=== VENDOR NFC METADATA DIAGNOSTIC V7 ===")
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
                appendLine("vendor_share_mode_transaction_source=${state["vendor_share_mode_transaction_source"] ?: "<missing>"}")
                appendLine("vendor_binder_descriptor=${state["vendor_binder_descriptor"] ?: "<missing>"}")
                appendLine("vendor_share_mode_method=${state["vendor_share_mode_method"] ?: "<missing>"}")
                appendLine("vendor_meta_error=${state["vendor_meta_error"] ?: "<none>"}")
                appendLine()
                appendLine("vendor_meta_report=${state["vendor_meta_report"] ?: "<missing>"}")
                appendLine()

                val tx = state["vendor_share_mode_transaction"]?.toIntOrNull()
                if (state["vendor_meta_ready"] == "true" && tx != null && tx > 0) {
                    appendLine("RESULT=METADATA_READY")
                    appendLine("NEXT=inspect METHOD_SIG/GETTER entries to identify the exact Binder-return path and required parameters")
                } else {
                    appendLine("RESULT=METADATA_NOT_READY")
                    appendLine("NEXT=restart com.android.nfc or reboot so the latest LSPosed metadata probe runs")
                }
            }

            AppLogger.i("VENDOR_METADATA_DIAGNOSTIC_V7:\n$report")
            runCatching { File(cacheDir, "vendor_nfc_metadata_v7.txt").writeText(report) }
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
                while (c.moveToNext()) {
                    out[c.getString(0)] = c.getString(1)
                }
            }
        }
        return out
    }
}
