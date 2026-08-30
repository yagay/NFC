from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

p=ROOT/'app/src/main/java/com/yagay/nfcdoorcard/xposed/NfcInjectionModule.java'
s=p.read_text()
s=s.replace('''        if (activeTarget != null) putTarget(v, activeTarget);\n        SimConfig cfg = cachedConfig;\n        if (cfg.initialized) v.put("controller_epoch", cfg.controllerEpoch);\n        v.put("rf_restore_mode", restoreMode);''','''        if (activeTarget != null) putTarget(v, activeTarget);\n        // controller_epoch is authoritative lifecycle metadata. Never copy it from cachedConfig\n        // into generic asynchronous status writes, because a late status write must not roll back\n        // a newer adapter-reset epoch. Lifecycle/RF paths write epochs explicitly.\n        v.put("rf_restore_mode", restoreMode);''')
s=s.replace('''        persistRefreshRuntime("LIFECYCLE_INVALIDATED", "", reason, cfg.generation, false);\n        synchronized (this) {''','''        cachedConfig = cfg.withControllerEpoch(nextEpoch);\n        persistRefreshRuntime("LIFECYCLE_INVALIDATED", "", reason, cfg.generation, false);\n        synchronized (this) {''')
s=s.replace('''        clearTriggerWindow(cfg.generation);\n        cachedConfig = cfg.withControllerEpoch(nextEpoch);\n        Log.i(TAG, "CONTROLLER EPOCH advanced reason="''','''        clearTriggerWindow(cfg.generation);\n        Log.i(TAG, "CONTROLLER EPOCH advanced reason="''')
s=s.replace('''                else if ("controller_epoch".equals(key)) controllerEpoch = parseLong(value, 0L);\n                else if ("controller_epoch".equals(key)) controllerEpoch = parseLong(value, 0L);''','''                else if ("controller_epoch".equals(key)) controllerEpoch = parseLong(value, 0L);''')
p.write_text(s)

p=ROOT/'app/src/main/java/com/yagay/nfcdoorcard/ConfigProvider.kt'
s=p.read_text()
anchor='''        val incoming = ContentValues(values)\n        val prefs = prefs()\n        val currentGeneration = (prefs.all[KEY_COMMAND_GENERATION] as? Number)?.toLong() ?: 0L\n'''
replacement='''        val incoming = ContentValues(values)\n        val prefs = prefs()\n        val currentControllerEpoch = (prefs.all[KEY_CONTROLLER_EPOCH] as? Number)?.toLong() ?: 0L\n        val incomingControllerEpoch = incoming.getAsLong(KEY_CONTROLLER_EPOCH)\n        if (incomingControllerEpoch != null && incomingControllerEpoch < currentControllerEpoch) {\n            // Controller lifecycle epochs are monotonic. Generic/diagnostic writes can arrive late,\n            // but must never resurrect RF proof from an older controller lifecycle.\n            Log.i("NfcConfigProvider", "Ignored stale controller_epoch=$incomingControllerEpoch current=$currentControllerEpoch uid=${Binder.getCallingUid()}")\n            incoming.remove(KEY_CONTROLLER_EPOCH)\n        }\n        val currentGeneration = (prefs.all[KEY_COMMAND_GENERATION] as? Number)?.toLong() ?: 0L\n'''
if anchor not in s: raise SystemExit('ConfigProvider insert anchor not found')
s=s.replace(anchor,replacement)
p.write_text(s)
