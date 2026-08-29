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
 * Every step validates the Binder descriptor before continuing.
 */
class VendorNfcController {
    companion object {
        private const val INFC_DESCRIPTOR = "android.nfc.INfcAdapter"
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
        return try {
            // Initialize the framework-side NFC service proxy first.
            runCatching { NfcAdapter.getDefaultAdapter(null) }

            val service = NfcAdapter::class.java.getDeclaredField("sService").apply {
                isAccessible = true
            }.get(null) ?: return Result(false, enabled, "INFC_SERVICE", "NfcAdapter.sService is null")

            val mainBinder = toBinder(service)
                ?: return Result(false, enabled, "MAIN_BINDER", "Cannot obtain INfcAdapter binder")
            val mainDescriptor = runCatching { mainBinder.interfaceDescriptor }.getOrNull().orEmpty()
            if (mainDescriptor != INFC_DESCRIPTOR) {
                return Result(false, enabled, "MAIN_DESCRIPTOR", "Unexpected descriptor=$mainDescriptor")
            }

            val vendorBinder = getVendorBinder(mainBinder)
                ?: return Result(false, enabled, "GET_VENDOR_BINDER", "transaction 6 returned null/rejected")
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
