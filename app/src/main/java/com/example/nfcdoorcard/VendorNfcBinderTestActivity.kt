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
 * V5 diagnostic page.
 *
 * This page does not send Vendor NFC Binder transactions. It only reads metadata already
 * published by the com.android.nfc-side read-only metadata probe so the user can copy one
 * self-contained report without hunting through the Provider screen.
 */
class VendorNfcBinderTestActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Vendor NFC 元数据测试 V5"

        output = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            text = "V5 只读取 com.android.nfc 侧元数据探针结果，不发送任何 Vendor Binder transaction。\n\n安装新 APK 后请重启手机，再点下方按钮。"
        }

        runButton = Button(this).apply {
            text = "读取 Vendor NFC 元数据 V5"
            setOnClickListener { runMetadataCheck() }
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

    private fun runMetadataCheck() {
        runButton.isEnabled = false
        output.text = "读取中..."

        executor.execute {
            val state = readProviderMap()
            val report = buildString {
                appendLine("=== VENDOR NFC METADATA TEST V5 ===")
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
                appendLine("vendor_meta_report=${state["vendor_meta_report"] ?: "<missing>"}")
                appendLine("vendor_meta_error=${state["vendor_meta_error"] ?: "<none>"}")
                appendLine()

                val tx = state["vendor_share_mode_transaction"]?.toIntOrNull()
                when {
                    state["vendor_meta_ready"] == "true" && tx != null && tx > 0 -> {
                        appendLine("RESULT=METADATA_READY")
                        appendLine("NEXT=transaction code is available; inspect the real Binder-return path without guessing codes")
                    }
                    state["vendor_meta_error"]?.isNotBlank() == true -> {
                        appendLine("RESULT=METADATA_PROBE_ERROR")
                    }
                    else -> {
                        appendLine("RESULT=METADATA_NOT_LOADED")
                        appendLine("NEXT=confirm #256+ APK installed and com.android.nfc restarted so the second LSPosed module entry loads")
                    }
                }
            }

            AppLogger.i("VENDOR_METADATA_TEST_V5:\n$report")
            runCatching { File(cacheDir, "vendor_nfc_metadata_test.txt").writeText(report) }
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
