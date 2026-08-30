from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]

# ConfigProvider: add controller lifecycle epoch keys. controller_epoch is deliberately NOT
# terminal-owned: NFC OFF must be allowed to advance it while preserving a successful APPLY command.
p = ROOT/'app/src/main/java/com/yagay/nfcdoorcard/ConfigProvider.kt'
s=p.read_text()
s=s.replace('const val STATE_SCHEMA_VERSION = 6','const val STATE_SCHEMA_VERSION = 7')
s=s.replace('''        const val KEY_RF_VERIFICATION = "rf_verification"\n''','''        const val KEY_RF_VERIFICATION = "rf_verification"\n        const val KEY_CONTROLLER_EPOCH = "controller_epoch"\n        const val KEY_RF_CONTROLLER_EPOCH = "rf_controller_epoch"\n''')
s=s.replace('''            KEY_RF_PID, KEY_RF_GENERATION, KEY_RF_VERIFICATION,\n''','''            KEY_RF_PID, KEY_RF_GENERATION, KEY_RF_VERIFICATION, KEY_CONTROLLER_EPOCH, KEY_RF_CONTROLLER_EPOCH,\n''',1)
# Only rf_controller_epoch belongs to terminal RF evidence. controller_epoch must remain writable.
s=s.replace('''            KEY_RF_PID, KEY_RF_GENERATION, KEY_RF_VERIFICATION,\n            KEY_FULL_DIAG_STAGE''','''            KEY_RF_PID, KEY_RF_GENERATION, KEY_RF_VERIFICATION, KEY_RF_CONTROLLER_EPOCH,\n            KEY_FULL_DIAG_STAGE''',1)
p.write_text(s)

p=ROOT/'app/src/main/java/com/yagay/nfcdoorcard/xposed/NfcInjectionModule.java'
s=p.read_text()
# Replace prior invalidation implementation with epoch advancement.
start=s.index('    private void invalidateRfEvidenceForAdapterReset(String reason) {')
end=s.index('\n    /**\n     * Closed-loop lifecycle APPLY recovery.', start)
replacement='''    private void invalidateRfEvidenceForAdapterReset(String reason) {\n        SimConfig cfg = readConfig();\n        if (!cfg.initialized || !cfg.active || cfg.generation <= 0L) return;\n        long nextEpoch = Math.max(cfg.controllerEpoch + 1L, System.currentTimeMillis());\n        ContentValues v = baseHookState();\n        v.put("state_generation", cfg.generation);\n        // controller_epoch is lifecycle metadata, not terminal command/RF evidence. ConfigProvider\n        // intentionally permits it to advance after a successful APPLY. Existing RF evidence is\n        // then stale because rf_controller_epoch no longer matches.\n        v.put("controller_epoch", nextEpoch);\n        persistRefreshRuntime("LIFECYCLE_INVALIDATED", "", reason, cfg.generation, false);\n        writeValuesWithRetry(v, 8, 75L);\n        synchronized (this) {\n            lifecycleReapplyPending = false;\n            lifecycleRecoveryGeneration = Long.MIN_VALUE;\n            lifecycleRecoveryStartedAt = 0L;\n        }\n        clearTriggerWindow(cfg.generation);\n        cachedConfig = cfg.withControllerEpoch(nextEpoch);\n        Log.i(TAG, "CONTROLLER EPOCH advanced reason=" + reason + " generation=" + cfg.generation +\n                " oldEpoch=" + cfg.controllerEpoch + " newEpoch=" + nextEpoch + " uid=" + cfg.uid +\n                " pid=" + Process.myPid());\n    }\n'''
s=s[:start]+replacement+s[end:]
# isLifecycleVerified: collect and compare epochs.
s=s.replace('''        long rfGeneration = Long.MIN_VALUE;\n        int rfPid = 0;\n        boolean accepted = false;\n        String effective = "", confidence = "", rfUid = "";''','''        long rfGeneration = Long.MIN_VALUE;\n        long controllerEpoch = 0L, rfControllerEpoch = Long.MIN_VALUE;\n        int rfPid = 0;\n        boolean accepted = false;\n        String effective = "", confidence = "", rfUid = "";''')
s=s.replace('''                else if ("rf_uid".equals(key)) rfUid = normalizeUid(value);''','''                else if ("rf_uid".equals(key)) rfUid = normalizeUid(value);\n                else if ("controller_epoch".equals(key)) controllerEpoch = parseLong(value, 0L);\n                else if ("rf_controller_epoch".equals(key)) rfControllerEpoch = parseLong(value, Long.MIN_VALUE);''')
s=s.replace('''        return rfGeneration == generation && rfPid == pid && accepted && "ACTIVE".equals(effective) &&\n                "VERIFIED".equals(confidence) && normalizeUid(uid).equals(rfUid);''','''        return rfGeneration == generation && rfPid == pid && accepted && controllerEpoch > 0L &&\n                rfControllerEpoch == controllerEpoch && "ACTIVE".equals(effective) &&\n                "VERIFIED".equals(confidence) && normalizeUid(uid).equals(rfUid);''')
# Every successful/failure RF evidence gets current controller epoch; successful lifecycle will establish match.
s=s.replace('''        v.put("rf_generation", cfg.generation);\n        v.put("rf_verification", "LIFECYCLE_REAPPLY_FAILED");''','''        v.put("rf_generation", cfg.generation);\n        v.put("rf_controller_epoch", cfg.controllerEpoch);\n        v.put("rf_verification", "LIFECYCLE_REAPPLY_FAILED");''')
s=s.replace('''        v.put("rf_generation", cfg.generation);\n        v.put("rf_verification", state.equals("APPLYING") ? "CONFIG_WRITE_PENDING" : "LIFECYCLE_PENDING");''','''        v.put("rf_generation", cfg.generation);\n        v.put("rf_controller_epoch", cfg.controllerEpoch);\n        v.put("rf_verification", state.equals("APPLYING") ? "CONFIG_WRITE_PENDING" : "LIFECYCLE_PENDING");''')
s=s.replace('''        v.put("rf_generation", cfg.generation);\n        v.put("rf_verification", confirmedTriggerGeneration == cfg.generation ?''','''        v.put("rf_generation", cfg.generation);\n        v.put("rf_controller_epoch", cfg.controllerEpoch);\n        v.put("rf_verification", confirmedTriggerGeneration == cfg.generation ?''')
s=s.replace('''        v.put("rf_generation", cfg.generation);\n        v.put("rf_verification", verification);''','''        v.put("rf_generation", cfg.generation);\n        v.put("rf_controller_epoch", cfg.controllerEpoch);\n        v.put("rf_verification", verification);''')
# base state surfaces epoch for diagnostics.
s=s.replace('''        if (activeTarget != null) putTarget(v, activeTarget);\n        v.put("rf_restore_mode", restoreMode);''','''        if (activeTarget != null) putTarget(v, activeTarget);\n        SimConfig cfg = cachedConfig;\n        if (cfg.initialized) v.put("controller_epoch", cfg.controllerEpoch);\n        v.put("rf_restore_mode", restoreMode);''')
# readConfig parses controller_epoch.
s=s.replace('''        long generation = 0L, consumed = Long.MIN_VALUE, handled = Long.MIN_VALUE;''','''        long generation = 0L, consumed = Long.MIN_VALUE, handled = Long.MIN_VALUE, controllerEpoch = 0L;''')
s=s.replace('''                else if ("command_pid".equals(key)) commandPid = (int) parseLong(value, 0L);''','''                else if ("command_pid".equals(key)) commandPid = (int) parseLong(value, 0L);\n                else if ("controller_epoch".equals(key)) controllerEpoch = parseLong(value, 0L);''')
s=s.replace('''            return new SimConfig(true, active, uid, diagnostics, generation, consumed, handled, action, status, commandPid);''','''            // Schema migration or first install may not have an epoch yet. Seed it without\n            // declaring RF success; subsequent verified writes will record this epoch.\n            if (controllerEpoch <= 0L) controllerEpoch = 1L;\n            return new SimConfig(true, active, uid, diagnostics, generation, consumed, handled, action, status, commandPid, controllerEpoch);''')
# SimConfig field/constructor helper.
s=s.replace('''        final long generation, consumedGeneration, handledGeneration;\n        final int commandPid;\n        SimConfig(boolean initialized, boolean active, String uid, boolean diagnostics, long generation,\n                  long consumedGeneration, long handledGeneration, String commandAction, String commandStatus, int commandPid) {''','''        final long generation, consumedGeneration, handledGeneration, controllerEpoch;\n        final int commandPid;\n        SimConfig(boolean initialized, boolean active, String uid, boolean diagnostics, long generation,\n                  long consumedGeneration, long handledGeneration, String commandAction, String commandStatus, int commandPid, long controllerEpoch) {''')
s=s.replace('''            this.commandAction = commandAction; this.commandStatus = commandStatus; this.commandPid = commandPid;\n        }\n        static SimConfig uninitialized() { return new SimConfig(false, false, null, false, 0L, Long.MIN_VALUE, Long.MIN_VALUE, "", "", 0); }''','''            this.commandAction = commandAction; this.commandStatus = commandStatus; this.commandPid = commandPid;\n            this.controllerEpoch = controllerEpoch;\n        }\n        SimConfig withControllerEpoch(long epoch) {\n            return new SimConfig(initialized, active, uid, diagnostics, generation, consumedGeneration, handledGeneration,\n                    commandAction, commandStatus, commandPid, epoch);\n        }\n        static SimConfig uninitialized() { return new SimConfig(false, false, null, false, 0L, Long.MIN_VALUE, Long.MIN_VALUE, "", "", 0, 0L); }''')
p.write_text(s)

# RuntimeStatusRepository: stale if controller lifecycle epoch does not match RF evidence.
p=ROOT/'app/src/main/java/com/yagay/nfcdoorcard/RuntimeStatusRepository.kt'
s=p.read_text()
s=s.replace('''        val rawRfStatus = map[ConfigProvider.KEY_RF_STATUS] ?: "IDLE"\n        val rfFresh = currentPid > 0 && rfPid > 0 && rfPid == currentPid && (runtimePid == 0 || runtimePid == currentPid)''','''        val rawRfStatus = map[ConfigProvider.KEY_RF_STATUS] ?: "IDLE"\n        val controllerEpoch = map[ConfigProvider.KEY_CONTROLLER_EPOCH]?.toLongOrNull() ?: 0L\n        val rfControllerEpoch = map[ConfigProvider.KEY_RF_CONTROLLER_EPOCH]?.toLongOrNull() ?: Long.MIN_VALUE\n        val controllerFresh = controllerEpoch > 0L && rfControllerEpoch == controllerEpoch\n        val rfFresh = controllerFresh && currentPid > 0 && rfPid > 0 && rfPid == currentPid && (runtimePid == 0 || runtimePid == currentPid)''')
p.write_text(s)

# Version/schema behavior changed substantially.
p=ROOT/'app/build.gradle.kts'
s=p.read_text()
s=s.replace('versionCode = 42','versionCode = 43').replace('versionName = "1.0.41"','versionName = "1.0.42"')
s=s.replace('hook build 29; adapter OFF/ON RF invalidation + automatic lifecycle reapply;','hook build 30; controller-epoch lifecycle verification + automatic OFF/ON reapply;')
s=s.replace('buildConfigField("int", "HOOK_BUILD", "29")','buildConfigField("int", "HOOK_BUILD", "30")')
p.write_text(s)
