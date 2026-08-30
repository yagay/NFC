package com.yagay.nfcdoorcard

enum class LogSource(val label: String) {
    STATUS("状态"), LSPOSED("LSPosed"), KERNEL_SU("KernelSU"), SYSTEM("系统"),
    NFC("NFC"), HAL("HAL"), PROVIDER("Provider"), APP("App")
}

enum class StatusTone { OK, STOCK, IDLE, BUSY, WARNING, ERROR }

data class RuntimeStatus(
    val appBuild: Int = 0,
    val hookBuild: Int = 0,
    val currentPid: Int = 0,
    val runtimePid: Int = 0,
    val scopePid: Int = 0,
    val hookPid: Int = 0,
    val scopeOk: Boolean = false,
    val hookInstalled: Boolean = false,
    val simulationEnabled: Boolean = false,
    val selectedUid: String? = null,
    val commandGeneration: Long = 0,
    val consumedGeneration: Long = Long.MIN_VALUE,
    val handledGeneration: Long = Long.MIN_VALUE,
    val commandAction: String = "",
    val commandStatus: String = "IDLE",
    val commandDetail: String? = null,
    val commandPid: Int = 0,
    val operationState: String = "IDLE",
    val effectiveState: String = "UNKNOWN",
    val verificationConfidence: String = "NONE",
    val rfAccepted: Boolean = false,
    val rfStatus: String = "IDLE",
    val rfUid: String? = null,
    val rfSource: String? = null,
    val rfResult: String? = null,
    val rfNativeResult: String? = null,
    val rfNativeResultType: String? = null,
    val rfError: String? = null,
    val rfPid: Int = 0,
    val rfGeneration: Long = 0,
    val rfVerification: String? = null,
    val fullDiagStage: String? = null,
    val fullDiagSummary: String? = null
)
