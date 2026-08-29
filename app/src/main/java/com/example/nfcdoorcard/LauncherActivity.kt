package com.example.nfcdoorcard

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "NFC Expert Pro"

        val titleView = TextView(this).apply {
            text = "NFC Expert Pro\nHeyTap Bridge"
            textSize = 20f
            setPadding(0, 0, 0, 24)
        }

        val mainButton = Button(this).apply {
            text = "进入 HeyTap NFC 控制界面"
            setOnClickListener {
                startActivity(Intent(this@LauncherActivity, NfcControlActivity::class.java))
            }
        }

        val legacyButton = Button(this).apply {
            text = "旧 NFC 主界面（诊断）"
            setOnClickListener {
                startActivity(Intent(this@LauncherActivity, MainActivity::class.java))
            }
        }

        val testButton = Button(this).apply {
            text = "Vendor NFC 旧测试页"
            setOnClickListener {
                startActivity(Intent(this@LauncherActivity, VendorNfcBinderTestActivity::class.java))
            }
        }

        val hint = TextView(this).apply {
            text = "新控制界面不直接调用 Vendor Binder，也不重启 NFC。App 只写 Provider；LSPosed 在 com.heytap.accessory 进程中监听状态并执行 Share Mode。"
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
            addView(titleView, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(mainButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(legacyButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(testButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(hint, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
    }
}
