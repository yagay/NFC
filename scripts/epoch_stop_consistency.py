from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

p=ROOT/'app/src/main/java/com/yagay/nfcdoorcard/ConfigProvider.kt'
s=p.read_text()
old='''        if (stockLifecycleAdoption) {\n            incoming.put(KEY_STATE_GENERATION, currentGeneration)\n            incoming.put(KEY_OPERATION_STATE, "IDLE")'''
new='''        if (stockLifecycleAdoption) {\n            // A new NFC process implies a fresh controller lifecycle. Advance the controller epoch\n            // and bind the STOCK proof to that same epoch so stale pre-restart RF evidence cannot\n            // be reused.\n            val previousControllerEpoch = (prefs.all[KEY_CONTROLLER_EPOCH] as? Number)?.toLong() ?: 0L\n            val stockControllerEpoch = maxOf(previousControllerEpoch + 1L, System.currentTimeMillis())\n            incoming.put(KEY_CONTROLLER_EPOCH, stockControllerEpoch)\n            incoming.put(KEY_RF_CONTROLLER_EPOCH, stockControllerEpoch)\n            incoming.put(KEY_STATE_GENERATION, currentGeneration)\n            incoming.put(KEY_OPERATION_STATE, "IDLE")'''
if old not in s: raise SystemExit('stock lifecycle adoption anchor not found')
s=s.replace(old,new)
p.write_text(s)

p=ROOT/'app/src/main/java/com/yagay/nfcdoorcard/MainActivity.kt'
s=p.read_text()
old='''                val currentPid = currentNfcPid().toIntOrNull() ?: state.currentPid\n                contentResolver.insert(ConfigProvider.URI, ContentValues().apply {'''
new='''                val currentPid = currentNfcPid().toIntOrNull() ?: state.currentPid\n                val providerBeforeStockConfirm = readProviderMap()\n                val previousControllerEpoch = providerBeforeStockConfirm[ConfigProvider.KEY_CONTROLLER_EPOCH]?.toLongOrNull() ?: 0L\n                val stockControllerEpoch = maxOf(previousControllerEpoch + 1L, System.currentTimeMillis())\n                contentResolver.insert(ConfigProvider.URI, ContentValues().apply {'''
if old not in s: raise SystemExit('main fallback currentPid anchor not found')
s=s.replace(old,new)
old2='''                    put(ConfigProvider.KEY_RF_PID, currentPid)\n                    put(ConfigProvider.KEY_RF_GENERATION, generation)\n                    put(ConfigProvider.KEY_RF_VERIFICATION, "PROCESS_RESTART")'''
new2='''                    put(ConfigProvider.KEY_RF_PID, currentPid)\n                    put(ConfigProvider.KEY_RF_GENERATION, generation)\n                    put(ConfigProvider.KEY_CONTROLLER_EPOCH, stockControllerEpoch)\n                    put(ConfigProvider.KEY_RF_CONTROLLER_EPOCH, stockControllerEpoch)\n                    put(ConfigProvider.KEY_RF_VERIFICATION, "PROCESS_RESTART")'''
if old2 not in s: raise SystemExit('main fallback rf anchor not found')
s=s.replace(old2,new2)
p.write_text(s)
