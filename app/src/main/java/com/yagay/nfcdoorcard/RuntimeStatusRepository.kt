package com.yagay.nfcdoorcard

import android.database.ContentObserver
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.yagay.nfcdoorcard.system.NfcSystemService
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
        val rfPid = map[ConfigProvider.KEY_RF_PID]?.toIntOrNull() ?: 0
        val commandPid = map[ConfigProvider.KEY_COMMAND_PID]?.toIntOrNull() ?: 0
        val currentPid = rootPid ?: runtimePid.takeIf { it > 0 }
            ?: hookPid.takeIf { it > 0 } ?: commandPid.takeIf { it > 0 } ?: scopePid.takeIf { it > 0 } ?: rfPid
        val hookBuild = map[ConfigProvider.KEY_HOOK_BUILD]?.toIntOrNull() ?: 0
        val rawRfStatus = map[ConfigProvider.KEY_RF_STATUS] ?: "IDLE"
        val controllerEpoch = map[ConfigProvider.KEY_CONTROLLER_EPOCH]?.toLongOrNull() ?: 0L
        val rfControllerEpoch = map[ConfigProvider.KEY_RF_CONTROLLER_EPOCH]?.toLongOrNull() ?: Long.MIN_VALUE
        val rfFresh = RfFreshness.isFresh(
            currentPid = currentPid,
            runtimePid = runtimePid,
            rfPid = rfPid,
            controllerEpoch = controllerEpoch,
            rfControllerEpoch = rfControllerEpoch
        )
        val restartTransition = map[ConfigProvider.KEY_COMMAND_STATUS] == "RESTART_REQUIRED" &&
            map[ConfigProvider.KEY_COMMAND_GENERATION]?.toLongOrNull() == map[ConfigProvider.KEY_RF_GENERATION]?.toLongOrNull()
        val visibleRfStatus = when {
            rawRfStatus == "IDLE" -> "IDLE"
            rfFresh -> rawRfStatus
            restartTransition -> "RESETTING($rawRfStatus)"
            rfPid == 0 && rawRfStatus in setOf("WAITING", "APPLYING", "STOPPING") -> rawRfStatus
            else -> "STALE($rawRfStatus)"
        }
        val semanticVisible = rfFresh || rfPid == 0 || restartTransition
        return RuntimeStatus(
            appBuild = map[ConfigProvider.KEY_APP_BUILD]?.toIntOrNull() ?: 0,
            hookBuild = hookBuild,
            currentPid = currentPid,
            runtimePid = runtimePid,
            scopePid = scopePid,
            hookPid = hookPid,
            scopeOk = currentPid > 0 && scopePid == currentPid && map[ConfigProvider.KEY_SCOPE_OK].toBoolean(),
            hookInstalled = currentPid > 0 && hookPid == currentPid && map[ConfigProvider.KEY_HOOK_INSTALLED].toBoolean(),
            simulationEnabled = map[ConfigProvider.KEY_SIMULATION_ENABLED].toBoolean(),
            selectedUid = map[ConfigProvider.KEY_UID]?.takeIf { it.isNotBlank() },
            commandGeneration = map[ConfigProvider.KEY_COMMAND_GENERATION]?.toLongOrNull() ?: 0L,
            consumedGeneration = map[ConfigProvider.KEY_COMMAND_CONSUMED_GENERATION]?.toLongOrNull() ?: Long.MIN_VALUE,
            handledGeneration = map[ConfigProvider.KEY_COMMAND_HANDLED_GENERATION]?.toLongOrNull() ?: Long.MIN_VALUE,
            commandAction = map[ConfigProvider.KEY_COMMAND_ACTION].orEmpty(),
            commandStatus = map[ConfigProvider.KEY_COMMAND_STATUS] ?: "IDLE",
            commandDetail = map[ConfigProvider.KEY_COMMAND_DETAIL]?.takeIf { it.isNotBlank() },
            commandPid = commandPid,
            operationState = if (semanticVisible) map[ConfigProvider.KEY_OPERATION_STATE] ?: "IDLE" else "STALE",
            effectiveState = if (semanticVisible) map[ConfigProvider.KEY_EFFECTIVE_STATE] ?: "UNKNOWN" else "UNKNOWN",
            verificationConfidence = if (semanticVisible) map[ConfigProvider.KEY_VERIFICATION_CONFIDENCE] ?: "NONE" else "NONE",
            rfAccepted = rfFresh && map[ConfigProvider.KEY_RF_ACCEPTED].toBoolean(),
            rfStatus = visibleRfStatus,
            rfUid = if (rfFresh) map[ConfigProvider.KEY_RF_UID]?.takeIf { it.isNotBlank() } else null,
            rfSource = if (rfFresh) map[ConfigProvider.KEY_RF_SOURCE]?.takeIf { it.isNotBlank() } else null,
            rfResult = if (rfFresh) map[ConfigProvider.KEY_RF_RESULT]?.takeIf { it.isNotBlank() } else null,
            rfNativeResult = if (rfFresh) map[ConfigProvider.KEY_RF_NATIVE_RESULT]?.takeIf { it.isNotBlank() } else null,
            rfNativeResultType = if (rfFresh) map[ConfigProvider.KEY_RF_NATIVE_RESULT_TYPE]?.takeIf { it.isNotBlank() } else null,
            rfError = if (rfFresh) map[ConfigProvider.KEY_RF_ERROR]?.takeIf { it.isNotBlank() } else null,
            rfPid = rfPid,
            rfGeneration = map[ConfigProvider.KEY_RF_GENERATION]?.toLongOrNull() ?: 0L,
            rfVerification = if (rfFresh) map[ConfigProvider.KEY_RF_VERIFICATION]?.takeIf { it.isNotBlank() } else if (restartTransition) "LIFECYCLE_PENDING" else null,
            fullDiagStage = map[ConfigProvider.KEY_FULL_DIAG_STAGE]?.takeIf { it.isNotBlank() },
            fullDiagSummary = map[ConfigProvider.KEY_FULL_DIAG_SUMMARY]?.takeIf { it.isNotBlank() }
        )
    }
}
