package com.example.nfcdoorcard

import android.database.ContentObserver
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.nfcdoorcard.system.NfcSystemService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

class RuntimeStatusRepository(context: Context, private val nfcSystemService: NfcSystemService) {
    private val resolver = context.applicationContext.contentResolver

    fun observeChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) { trySend(Unit) }
        }
        resolver.registerContentObserver(ConfigProvider.URI, true, observer)
        trySend(Unit)
        awaitClose { runCatching { resolver.unregisterContentObserver(observer) } }
    }.conflate()

    fun readProviderMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        runCatching {
            resolver.query(ConfigProvider.URI, null, null, null, null)?.use { c ->
                while (c.moveToNext()) map[c.getString(0)] = c.getString(1)
            }
        }
        return map
    }

    fun read(includeRootPid: Boolean = false): RuntimeStatus {
        val map = readProviderMap()
        val rootPid = if (includeRootPid) nfcSystemService.currentNfcPid().toIntOrNull() else null
        return decode(map, rootPid)
    }

    fun isCurrentCommandGeneration(generation: Long): Boolean =
        readProviderMap()[ConfigProvider.KEY_COMMAND_GENERATION]?.toLongOrNull() == generation

    fun simulationEnabled(): Boolean = readProviderMap()[ConfigProvider.KEY_SIMULATION_ENABLED].toBoolean()

    fun decode(map: Map<String, String>, rootPid: Int?): RuntimeStatus {
        val scopePid = map[ConfigProvider.KEY_SCOPE_PID]?.toIntOrNull() ?: 0
        val hookPid = map[ConfigProvider.KEY_HOOK_PID]?.toIntOrNull() ?: 0
        val runtimePid = map[ConfigProvider.KEY_RUNTIME_PID]?.toIntOrNull() ?: 0
        val storedRfPid = map[ConfigProvider.KEY_RF_PID]?.toIntOrNull() ?: 0
        val commandPid = map[ConfigProvider.KEY_COMMAND_PID]?.toIntOrNull() ?: 0
        val currentPid = rootPid ?: runtimePid.takeIf { it > 0 }
            ?: hookPid.takeIf { it > 0 } ?: commandPid.takeIf { it > 0 } ?: scopePid.takeIf { it > 0 } ?: storedRfPid
        val hookBuild = map[ConfigProvider.KEY_HOOK_BUILD]?.toIntOrNull() ?: 0
        val rawRfStatus = map[ConfigProvider.KEY_RF_STATUS] ?: "IDLE"
        val simulationEnabled = map[ConfigProvider.KEY_SIMULATION_ENABLED].toBoolean()
        val commandGeneration = map[ConfigProvider.KEY_COMMAND_GENERATION]?.toLongOrNull() ?: 0L
        val consumedGeneration = map[ConfigProvider.KEY_COMMAND_CONSUMED_GENERATION]?.toLongOrNull() ?: Long.MIN_VALUE
        val handledGeneration = map[ConfigProvider.KEY_COMMAND_HANDLED_GENERATION]?.toLongOrNull() ?: Long.MIN_VALUE
        val commandAction = map[ConfigProvider.KEY_COMMAND_ACTION].orEmpty()
        val commandStatus = map[ConfigProvider.KEY_COMMAND_STATUS] ?: "IDLE"
        val rfGeneration = map[ConfigProvider.KEY_RF_GENERATION]?.toLongOrNull() ?: 0L
        val providerOperation = map[ConfigProvider.KEY_OPERATION_STATE] ?: "IDLE"
        val providerEffective = map[ConfigProvider.KEY_EFFECTIVE_STATE] ?: "UNKNOWN"
        val providerConfidence = map[ConfigProvider.KEY_VERIFICATION_CONFIDENCE] ?: "NONE"
        val providerRfAccepted = map[ConfigProvider.KEY_RF_ACCEPTED].toBoolean()
        val providerRfVerification = map[ConfigProvider.KEY_RF_VERIFICATION].orEmpty()

        val rfFresh = currentPid > 0 && storedRfPid > 0 && storedRfPid == currentPid && (runtimePid == 0 || runtimePid == currentPid)
        val restartTransition = commandStatus == "RESTART_REQUIRED" && commandGeneration == rfGeneration

        // A completed STOP is still trustworthy after com.android.nfc gets a new PID. The process
        // restart itself discards the injected controller state and reloads stock RF. Once the new
        // process has a live Hook/runtime PID, retain the terminal STOCK/VERIFIED fact instead of
        // degrading it to STALE merely because the evidence was recorded by the previous PID.
        // This is a read-side lifecycle adoption only: no old command is replayed and Provider's
        // monotonic terminal-generation protection remains authoritative.
        val stockAdoptedAfterRestart = !simulationEnabled &&
            currentPid > 0 && runtimePid == currentPid && hookPid == currentPid &&
            commandAction == "STOP" && commandStatus == "SUCCESS" && commandGeneration > 0L &&
            handledGeneration == commandGeneration && rfGeneration == commandGeneration &&
            providerOperation == "IDLE" && providerEffective == "STOCK" &&
            providerConfidence == "VERIFIED" && providerRfAccepted &&
            storedRfPid > 0 && storedRfPid != currentPid &&
            (rawRfStatus == "RF_STOCK_RESTORED_BY_RESTART" || providerRfVerification == "PROCESS_RESTART")

        val visibleRfStatus = when {
            rawRfStatus == "IDLE" -> "IDLE"
            rfFresh -> rawRfStatus
            stockAdoptedAfterRestart -> "RF_STOCK_CONFIRMED_AFTER_RESTART"
            restartTransition -> "RESETTING($rawRfStatus)"
            storedRfPid == 0 && rawRfStatus in setOf("WAITING", "APPLYING", "STOPPING") -> rawRfStatus
            else -> "STALE($rawRfStatus)"
        }
        val semanticVisible = rfFresh || storedRfPid == 0 || restartTransition || stockAdoptedAfterRestart
        val effectiveRfPid = if (stockAdoptedAfterRestart) currentPid else storedRfPid

        return RuntimeStatus(
            appBuild = map[ConfigProvider.KEY_APP_BUILD]?.toIntOrNull() ?: 0,
            hookBuild = hookBuild,
            currentPid = currentPid,
            runtimePid = runtimePid,
            scopePid = scopePid,
            hookPid = hookPid,
            scopeOk = currentPid > 0 && scopePid == currentPid && map[ConfigProvider.KEY_SCOPE_OK].toBoolean(),
            hookInstalled = currentPid > 0 && hookPid == currentPid && map[ConfigProvider.KEY_HOOK_INSTALLED].toBoolean(),
            simulationEnabled = simulationEnabled,
            selectedUid = map[ConfigProvider.KEY_UID]?.takeIf { it.isNotBlank() },
            commandGeneration = commandGeneration,
            consumedGeneration = consumedGeneration,
            handledGeneration = handledGeneration,
            commandAction = commandAction,
            commandStatus = commandStatus,
            commandDetail = map[ConfigProvider.KEY_COMMAND_DETAIL]?.takeIf { it.isNotBlank() },
            commandPid = commandPid,
            operationState = if (semanticVisible) providerOperation else "STALE",
            effectiveState = if (semanticVisible) providerEffective else "UNKNOWN",
            verificationConfidence = if (semanticVisible) providerConfidence else "NONE",
            rfAccepted = (rfFresh || stockAdoptedAfterRestart) && providerRfAccepted,
            rfStatus = visibleRfStatus,
            rfUid = if (rfFresh) map[ConfigProvider.KEY_RF_UID]?.takeIf { it.isNotBlank() } else null,
            rfSource = when {
                rfFresh -> map[ConfigProvider.KEY_RF_SOURCE]?.takeIf { it.isNotBlank() }
                stockAdoptedAfterRestart -> "process-start"
                else -> null
            },
            rfResult = when {
                rfFresh -> map[ConfigProvider.KEY_RF_RESULT]?.takeIf { it.isNotBlank() }
                stockAdoptedAfterRestart -> "0"
                else -> null
            },
            rfNativeResult = when {
                rfFresh -> map[ConfigProvider.KEY_RF_NATIVE_RESULT]?.takeIf { it.isNotBlank() }
                stockAdoptedAfterRestart -> "process-start"
                else -> null
            },
            rfNativeResultType = when {
                rfFresh -> map[ConfigProvider.KEY_RF_NATIVE_RESULT_TYPE]?.takeIf { it.isNotBlank() }
                stockAdoptedAfterRestart -> "lifecycle"
                else -> null
            },
            rfError = if (rfFresh) map[ConfigProvider.KEY_RF_ERROR]?.takeIf { it.isNotBlank() } else null,
            rfPid = effectiveRfPid,
            rfGeneration = rfGeneration,
            rfVerification = when {
                rfFresh -> providerRfVerification.takeIf { it.isNotBlank() }
                stockAdoptedAfterRestart -> "PROCESS_START"
                restartTransition -> "LIFECYCLE_PENDING"
                else -> null
            },
            fullDiagStage = if (stockAdoptedAfterRestart) "RF_STOCK_CONFIRMED_AFTER_RESTART"
                else map[ConfigProvider.KEY_FULL_DIAG_STAGE]?.takeIf { it.isNotBlank() },
            fullDiagSummary = if (stockAdoptedAfterRestart)
                "Terminal STOP survived NFC process restart; stock RF confirmed by new process lifecycle"
            else map[ConfigProvider.KEY_FULL_DIAG_SUMMARY]?.takeIf { it.isNotBlank() }
        )
    }
}
