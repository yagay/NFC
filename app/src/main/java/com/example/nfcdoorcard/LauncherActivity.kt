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
            text = "NFC Expert Pro\n请选择操作"
            textSize = 20f
            setPadding(0, 0, 0, 24)
        }

        val mainButton = Button(this).apply {
            text = "进入 NFC 主界面"
            setOnClickListener {
                startActivity(Intent(this@LauncherActivity, MainActivity::class.java))
            }
        }

        val testButton = Button(this).apply {
            text = "Vendor NFC 一次测试"
            setOnClickListener {
                startActivity(Intent(this@LauncherActivity, VendorNfcBinderTestActivity::class.java))
            }
        }

        val hint = TextView(this).apply {
            text = "测试步骤：先进入主界面启动目标卡模拟，返回后再进入 Vendor NFC 一次测试。测试过程不会自动打开分享页。"
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
            addView(titleView, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(mainButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(testButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(hint, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
    }
}
