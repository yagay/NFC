from pathlib import Path


def one(s, old, new, label):
    c=s.count(old)
    if c!=1: raise SystemExit(f'{label}: {c}')
    return s.replace(old,new,1)

# bump build
p=Path('app/build.gradle.kts'); s=p.read_text()
s=one(s,'versionCode = 27','versionCode = 28','versionCode')
s=one(s,'versionName = "1.0.26"','versionName = "1.0.27"','versionName')
s=one(s,'buildConfigField("int", "HOOK_BUILD", "21")','buildConfigField("int", "HOOK_BUILD", "22")','hook build')
p.write_text(s)

p=Path('app/src/main/java/com/example/nfcdoorcard/xposed/NfcInjectionModule.java'); s=p.read_text()
s=one(s,'import android.content.ContentValues;','import android.content.BroadcastReceiver;\nimport android.content.ContentValues;\nimport android.content.Context;\nimport android.content.Intent;\nimport android.content.IntentFilter;', 'imports content')
s=one(s,'import android.os.Process;','import android.os.Build;\nimport android.os.Process;', 'imports build')
s=one(s,'    private volatile ContentObserver commandObserver;\n    private volatile boolean observerRegistered;', '    private volatile ContentObserver commandObserver;\n    private volatile BroadcastReceiver adapterStateReceiver;\n    private volatile boolean observerRegistered;\n    private volatile boolean lifecycleReapplyPending;', 'fields')

old='''                persistRestoreState();
                if (!isGenerationCompleted(cfg.generation, pid)) {
                    completeCommand(cfg, "RF_UID_APPLIED", uidHex, rewritten.codecId, outcome,
                            "UID applied by verified target " + target.className + "#" + target.methodName +
                                    "; stopMode=" + restoreMode);
                }
'''
new='''                persistRestoreState();
                if (lifecycleReapplyPending) {
                    lifecycleReapplyPending = false;
                    completeLifecycleReapply(cfg, uidHex, rewritten.codecId, outcome, target);
                } else if (!isGenerationCompleted(cfg.generation, pid)) {
                    completeCommand(cfg, "RF_UID_APPLIED", uidHex, rewritten.codecId, outcome,
                            "UID applied by verified target " + target.className + "#" + target.methodName +
                                    "; stopMode=" + restoreMode);
                }
'''
s=one(s,old,new,'accepted branch')

old='''            app.getContentResolver().registerContentObserver(CONFIG_URI, true, observer);
            commandObserver = observer;
            observerRegistered = true;
            refreshConfigAndProcess("startup");
'''
new='''            app.getContentResolver().registerContentObserver(CONFIG_URI, true, observer);
            commandObserver = observer;
            observerRegistered = true;
            registerAdapterStateReceiver(app);
            refreshConfigAndProcess("startup");
'''
s=one(s,old,new,'bridge receiver')

marker='''    private void scheduleCommandRefresh(String reason) {
'''
insert='''    private void registerAdapterStateReceiver(Application app) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null || !"android.nfc.action.ADAPTER_STATE_CHANGED".equals(intent.getAction())) return;
                int state = intent.getIntExtra("android.nfc.extra.ADAPTER_STATE", -1);
                if (state == 3) {
                    commandExecutor.execute(() -> reapplyAfterAdapterOn("adapter_state_on"));
                } else if (state == 1 || state == 4) {
                    lifecycleReapplyPending = false;
                }
            }
        };
        IntentFilter filter = new IntentFilter("android.nfc.action.ADAPTER_STATE_CHANGED");
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(receiver, filter);
        adapterStateReceiver = receiver;
        Log.i(TAG, "ADAPTER STATE receiver registered pid=" + Process.myPid());
    }

    private void reapplyAfterAdapterOn(String reason) {
        sleep(350L);
        SimConfig cfg = readConfig();
        if (!cfg.initialized || !cfg.active || cfg.uid == null) return;
        cachedConfig = cfg;
        String uidHex = normalizeUid(cfg.uid);
        if (uidHex.length() != 8) return;

        lifecycleReapplyPending = true;
        ContentValues pending = baseHookState();
        pending.put("state_generation", cfg.generation);
        pending.put("rf_status", "RF_LIFECYCLE_REAPPLYING");
        pending.put("rf_uid", uidHex);
        pending.put("rf_source", activeCodec);
        pending.put("rf_accepted", false);
        pending.put("rf_error", "");
        pending.put("rf_pid", Process.myPid());
        pending.put("rf_generation", cfg.generation);
        pending.put("rf_verification", "LIFECYCLE_REAPPLY_PENDING");
        pending.put("operation_state", "APPLYING");
        pending.put("effective_state", "UNKNOWN");
        pending.put("verification_confidence", "LIFECYCLE_PENDING");
        pending.put("full_diag_stage", "LIFECYCLE_REAPPLY");
        pending.put("full_diag_summary", "NFC adapter turned on; reapplying saved UID without a new user command");
        writeValuesWithRetry(pending, 8, 75L);

        Log.i(TAG, "LIFECYCLE REAPPLY trigger reason=" + reason + " generation=" + cfg.generation +
                " uid=" + uidHex + " pid=" + Process.myPid());
        NfcProcessVendorController.Result trigger = vendorController.setShareMode(true);
        if (!trigger.success) {
            lifecycleReapplyPending = false;
            ContentValues failed = baseHookState();
            failed.put("state_generation", cfg.generation);
            failed.put("rf_status", "RF_LIFECYCLE_REAPPLY_FAILED");
            failed.put("rf_uid", uidHex);
            failed.put("rf_accepted", false);
            failed.put("rf_error", trigger.stage + ": " + trigger.detail);
            failed.put("rf_pid", Process.myPid());
            failed.put("rf_generation", cfg.generation);
            failed.put("rf_verification", "LIFECYCLE_REAPPLY_FAILED");
            failed.put("operation_state", "FAILED");
            failed.put("effective_state", "UNKNOWN");
            failed.put("verification_confidence", "NONE");
            failed.put("full_diag_stage", "LIFECYCLE_REAPPLY_FAILED");
            failed.put("full_diag_summary", trigger.stage + ": " + trigger.detail);
            writeValuesWithRetry(failed, 8, 75L);
        }
    }

'''
if s.count(marker)!=1: raise SystemExit('schedule marker')
s=s.replace(marker,insert+marker,1)

marker='''    private void completeControllerReinit(SimConfig cfg, String detail) {
'''
insert='''    private void completeLifecycleReapply(SimConfig cfg, String uid, String source,
                                          NativeOutcome outcome, HookTarget target) {
        ContentValues v = baseHookState();
        v.put("state_generation", cfg.generation);
        v.put("rf_status", "RF_UID_APPLIED");
        v.put("rf_uid", uid);
        v.put("rf_source", "lifecycle-reapply|" + (source == null ? "" : source));
        v.put("rf_result", outcome.value);
        v.put("rf_native_result", outcome.value);
        v.put("rf_native_result_type", outcome.type);
        v.put("rf_accepted", outcome.accepted);
        v.put("rf_error", "");
        v.put("rf_pid", Process.myPid());
        v.put("rf_generation", cfg.generation);
        v.put("rf_verification", "LIFECYCLE_REAPPLY_NATIVE_RESULT");
        v.put("operation_state", "ACTIVE");
        v.put("effective_state", "SIMULATED");
        v.put("verification_confidence", "NATIVE_ACCEPTED");
        v.put("full_diag_stage", "LIFECYCLE_REAPPLY_SUCCESS");
        v.put("full_diag_summary", "Saved UID automatically reapplied after NFC adapter restart via " +
                target.className + "#" + target.methodName);
        writeValuesWithRetry(v, 20, 100L);
        Log.i(TAG, "LIFECYCLE REAPPLY success generation=" + cfg.generation + " uid=" + uid +
                " target=" + target.fingerprint() + " pid=" + Process.myPid());
    }

'''
if s.count(marker)!=1: raise SystemExit('controller marker')
s=s.replace(marker,insert+marker,1)
p.write_text(s)
