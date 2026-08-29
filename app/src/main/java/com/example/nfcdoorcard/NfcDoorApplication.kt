package com.example.nfcdoorcard

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.nfc.NfcAdapter
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Keeps foreground tag dispatch out of the way while the phone is emulating a card.
 *
 * MainActivity normally enables foreground dispatch so physical cards can be read.
 * On this OxygenOS/NXP stack that foreground polling state can prevent the listen/card
 * emulation path from becoming externally readable until the activity pauses. The old
 * "one tap diagnostic + export" appeared to fix emulation because opening the share
 * chooser paused MainActivity and therefore disabled foreground dispatch.
 *
 * This application-level guard performs that transition immediately when
 * simulation_enabled changes, and keeps it correct across activity resume events.
 */
class NfcDoorApplication : Application(), Application.ActivityLifecycleCallbacks,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var resumedActivity = WeakReference<Activity>(null)
    private lateinit var configPrefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        configPrefs = getSharedPreferences("nfc_config", 0)
        configPrefs.registerOnSharedPreferenceChangeListener(this)
        registerActivityLifecycleCallbacks(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key != ConfigProvider.KEY_SIMULATION_ENABLED) return
        resumedActivity.get()?.let { activity ->
            activity.runOnUiThread { applyForegroundDispatchState(activity) }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        resumedActivity = WeakReference(activity)
        applyForegroundDispatchState(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity.get() === activity) resumedActivity.clear()
    }

    private fun applyForegroundDispatchState(activity: Activity) {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        val simulating = configPrefs.getBoolean(ConfigProvider.KEY_SIMULATION_ENABLED, false)
        if (simulating) {
            runCatching { adapter.disableForegroundDispatch(activity) }
                .onSuccess { AppLogger.i("DISPATCH: disabled for card emulation") }
                .onFailure { AppLogger.i("DISPATCH: disable failed ${it.javaClass.simpleName}: ${it.message}") }
            return
        }

        val pendingIntent = PendingIntent.getActivity(
            activity,
            0,
            Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        runCatching { adapter.enableForegroundDispatch(activity, pendingIntent, null, null) }
            .onSuccess { AppLogger.i("DISPATCH: enabled for physical card reading") }
            .onFailure { AppLogger.i("DISPATCH: enable failed ${it.javaClass.simpleName}: ${it.message}") }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
