package com.example.nfcdoorcard

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/** Keeps the original launcher component while opening the legacy main UI directly. */
class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
