package com.yagay.nfcdoorcard

/**
 * Pure RF freshness predicate shared by UI/runtime decoding.
 *
 * A com.android.nfc PID can survive an NFC adapter OFF/ON cycle, so PID equality alone is not
 * proof that an earlier controller RF configuration is still active. A proof is fresh only when
 * it belongs to the current controller lifecycle epoch as well as the current NFC process.
 */
internal object RfFreshness {
    fun isFresh(
        currentPid: Int,
        runtimePid: Int,
        rfPid: Int,
        controllerEpoch: Long,
        rfControllerEpoch: Long
    ): Boolean =
        controllerEpoch > 0L &&
            rfControllerEpoch == controllerEpoch &&
            currentPid > 0 &&
            rfPid > 0 &&
            rfPid == currentPid &&
            (runtimePid == 0 || runtimePid == currentPid)
}
