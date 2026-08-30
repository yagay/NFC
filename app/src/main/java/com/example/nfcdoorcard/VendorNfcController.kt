package com.example.nfcdoorcard

import android.nfc.NfcAdapter
import android.os.IBinder
import android.os.Parcel

/**
 * Production Binder bridge proven on the target device.
 *
 * INfcAdapter#getNfcAdapterVendorInterface(String): transaction 6, vendorName="vendor"
 * IVendorNfcAdapter#enableNfcShareMode(boolean): transaction 15
 *
 * The NFC process can be restarted while this app stays alive. NfcAdapter.sService may
 * then still point at the dead pre-restart Binder proxy, so always prefer a fresh
 * ServiceManager lookup and retry once if the first Binder becomes stale.
 */
class VendorNfcController {
    companion object {
        private const val INFC_DESCRIPTOR = "android.nfc.INfcAdapter"
        private const val NFC_SERVICE_NAME = "nfc"
        private const val TX_GET_VENDOR_INTERFACE = 6
        private const val VENDOR_NAME = "vendor"
        private const val VENDOR_DESCRIPTOR = "com.vendor.nfc.IVendorNfcAdapter"
        private const val TX_ENABLE_SHARE_MODE = 15
    }

    data class Result(
        val success: Boolean,
        val enabled: Boolean,
        val stage: String,
        val detail: String,
        val vendorDescriptor: String? = null
    )

    fun setShareMode(enabled: Boolean): Result {
        var lastFailure: Result? = null
        repeat(2) { attempt ->
            val result = setShareModeOnce(enabled, preferFresh = attempt > 0)
            if (result.success) return result
            lastFailure = result

            // A blank/wrong descriptor, dead Binder or null service after com.android.nfc
            // restart is transient. Re-resolve the system service and retry once.
            if (result.stage !in setOf("INFC_SERVICE", "MAIN_BINDER", "MAIN_DESCRIPTOR", "GET_VENDOR_BINDER", "EXCEPTION")) {
                return result
            }
            if (attempt == 0) Thread.sleep(120L)
        }
        return lastFailure ?: Result(false, enabled, "UNKNOWN", "Vendor Binder call failed")
    }

    private fun setShareModeOnce(enabled: Boolean, preferFresh: Boolean): Result {
        return try {
            val mainBinder = resolveMainBinder(preferFresh)
                ?: return Result(false, enabled, "MAIN_BINDER", "Cannot obtain live INfcAdapter binder")

            if (!mainBinder.isBinderAlive || !mainBinder.pingBinder()) {
                return Result(false, enabled, "MAIN_BINDER", "INfcAdapter binder is not alive")
            }

            val mainDescriptor = runCatching { mainBinder.interfaceDescriptor }.getOrNull().orEmpty()
            if (mainDescriptor != INFC_DESCRIPTOR) {
                return Result(false, enabled, "MAIN_DESCRIPTOR", "Unexpected descriptor=$mainDescriptor")
            }

            val vendorBinder = getVendorBinder(mainBinder)
                ?: return Result(false, enabled, "GET_VENDOR_BINDER", "transaction 6 returned null/rejected")
            if (!vendorBinder.isBinderAlive || !vendorBinder.pingBinder()) {
                return Result(false, enabled, "GET_VENDOR_BINDER", "Vendor binder is not alive")
            }

            val vendorDescriptor = runCatching { vendorBinder.interfaceDescriptor }.getOrNull().orEmpty()
            if (vendorDescriptor != VENDOR_DESCRIPTOR) {
                return Result(false, enabled, "VENDOR_DESCRIPTOR", "Unexpected descriptor=$vendorDescriptor", vendorDescriptor)
            }

            val accepted = transactShareMode(vendorBinder, enabled)
            Result(
                success = accepted,
                enabled = enabled,
                stage = if (accepted) "DONE" else "SHARE_MODE",
                detail = if (accepted) "enableNfcShareMode($enabled) accepted" else "transaction 15 rejected",
                vendorDescriptor = vendorDescriptor
            )
        } catch (t: Throwable) {
            Result(false, enabled, "EXCEPTION", "${t.javaClass.name}: ${t.message ?: "<no message>"}")
        }
    }

    private fun resolveMainBinder(preferFresh: Boolean): IBinder? {
        if (preferFresh) {
            serviceManagerBinder()?.let { if (it.isBinderAlive && it.pingBinder()) return it }
            nfcAdapterStaticBinder()?.let { if (it.isBinderAlive && it.pingBinder()) return it }
        } else {
            // ServiceManager is authoritative across NFC process restarts and avoids a
            // stale NfcAdapter.sService proxy. Keep sService only as a compatibility fallback.
            serviceManagerBinder()?.let { if (it.isBinderAlive && it.pingBinder()) return it }
            nfcAdapterStaticBinder()?.let { if (it.isBinderAlive && it.pingBinder()) return it }
        }
        return null
    }

    private fun serviceManagerBinder(): IBinder? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val method = sm.getDeclaredMethod("getService", String::class.java)
            method.isAccessible = true
            method.invoke(null, NFC_SERVICE_NAME) as? IBinder
        } catch (_: Throwable) {
            null
        }
    }

    private fun nfcAdapterStaticBinder(): IBinder? {
        return try {
            val service = NfcAdapter::class.java.getDeclaredField("sService").apply {
                isAccessible = true
            }.get(null) ?: return null
            toBinder(service)
        } catch (_: Throwable) {
            null
        }
    }

    private fun getVendorBinder(mainBinder: IBinder): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INFC_DESCRIPTOR)
            data.writeString(VENDOR_NAME)
            if (!mainBinder.transact(TX_GET_VENDOR_INTERFACE, data, reply, 0)) return null
            reply.setDataPosition(0)
            reply.readException()
            reply.readStrongBinder()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactShareMode(binder: IBinder, enabled: Boolean): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(VENDOR_DESCRIPTOR)
            data.writeBoolean(enabled)
            if (!binder.transact(TX_ENABLE_SHARE_MODE, data, reply, 0)) return false
            reply.setDataPosition(0)
            reply.readException()
            if (reply.dataAvail() >= 4) reply.readBoolean() else true
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun toBinder(value: Any?): IBinder? {
        if (value == null) return null
        if (value is IBinder) return value
        return try {
            val method = value.javaClass.methods.firstOrNull { it.name == "asBinder" && it.parameterCount == 0 }
                ?: value.javaClass.declaredMethods.firstOrNull { it.name == "asBinder" && it.parameterCount == 0 }
                ?: return null
            method.isAccessible = true
            method.invoke(value) as? IBinder
        } catch (_: Throwable) {
            null
        }
    }
}
