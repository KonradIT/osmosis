package com.chernowii.osmosis.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.chernowii.osmosis.duml.DjiMessage
import java.util.ArrayDeque
import java.util.UUID

/**
 * Connects to an Osmo over GATT and brings up the DUML channel:
 *   connect -> MTU 517 -> discover fff0 -> enable notifications on fff4 & fff5
 *   -> write [01 00] to fff4 (arm pairing) -> onReady().
 * All notifications (raw + best-effort DUML parse) are surfaced to the listener.
 *
 * Callbacks arrive on a binder thread; the listener implementation must marshal to UI.
 */
@SuppressLint("MissingPermission")
class GattClient(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onLog(s: String)
        fun onReady(gatt: GattClient)
        fun onNotification(sourceChar: UUID, raw: ByteArray, parsed: DjiMessage?)
        fun onDisconnected()
    }

    private var gatt: BluetoothGatt? = null
    private val cccdQueue = ArrayDeque<BluetoothGattCharacteristic>()

    @Volatile
    var negotiatedMtu: Int = 23
        private set

    fun connect(device: BluetoothDevice) {
        listener.onLog("GATT: connecting to ${device.address} ...")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
    }

    fun close() {
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
    }

    /** Write a DUML command frame to fff5 (write-without-response). */
    fun writeCommand(bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = g.getService(BleConstants.SERVICE_FFF0)?.getCharacteristic(BleConstants.CHAR_FFF5)
            ?: run { listener.onLog("GATT: fff5 missing, cannot write"); return false }
        return writeChar(g, ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
    }

    @Suppress("DEPRECATION")
    private fun writeChar(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = writeType
            ch.value = value
            g.writeCharacteristic(ch)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    listener.onLog("GATT: connected (status=$status); requesting MTU 517")
                    g.requestMtu(517)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    listener.onLog("GATT: disconnected (status=$status)")
                    listener.onDisconnected()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = mtu
            listener.onLog("GATT: MTU=$mtu (status=$status); discovering services")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(BleConstants.SERVICE_FFF0)
            if (svc == null) {
                listener.onLog(
                    "GATT: service fff0 NOT found. Present: " +
                        g.services.joinToString { short(it.uuid) }
                )
                return
            }
            listener.onLog("GATT: fff0 chars: " + svc.characteristics.joinToString { short(it.uuid) })
            cccdQueue.clear()
            for (u in listOf(BleConstants.CHAR_FFF4, BleConstants.CHAR_FFF5)) {
                svc.getCharacteristic(u)?.let {
                    g.setCharacteristicNotification(it, true)
                    cccdQueue.add(it)
                }
            }
            writeNextCccd(g)
        }

        @Suppress("DEPRECATION")
        private fun writeNextCccd(g: BluetoothGatt) {
            val ch = cccdQueue.poll() ?: run { armPairing(g); return }
            val d = ch.getDescriptor(BleConstants.CCCD)
            if (d == null) {
                listener.onLog("GATT: ${short(ch.uuid)} has no CCCD; skipping")
                writeNextCccd(g)
                return
            }
            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, value)
            } else {
                d.value = value
                g.writeDescriptor(d)
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            listener.onLog("GATT: notify enabled on ${short(descriptor.characteristic.uuid)} (status=$status)")
            writeNextCccd(g)
        }

        private fun armPairing(g: BluetoothGatt) {
            val ch = g.getService(BleConstants.SERVICE_FFF0)?.getCharacteristic(BleConstants.CHAR_FFF4)
            if (ch == null) {
                listener.onReady(this@GattClient)
                return
            }
            // Write-with-response so we can sequence pairing AFTER this completes (onCharacteristicWrite).
            val ok = writeChar(g, ch, byteArrayOf(0x01, 0x00), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            listener.onLog("GATT: writing [01 00] to fff4 (arm pairing) issued=$ok")
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (ch.uuid == BleConstants.CHAR_FFF4) {
                listener.onLog("GATT: fff4 arm write complete (status=$status)")
                listener.onReady(this@GattClient)
            }
        }

        // API <= 32 delivers notifications here.
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            dispatch(ch.uuid, ch.value ?: ByteArray(0))
        }

        // API 33+ delivers notifications here (value passed explicitly).
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            dispatch(ch.uuid, value)
        }
    }

    private fun dispatch(sourceChar: UUID, value: ByteArray) {
        val parsed = try {
            DjiMessage.fromBytes(value)
        } catch (_: Exception) {
            null
        }
        listener.onNotification(sourceChar, value, parsed)
    }

    private fun short(u: UUID) = u.toString().substring(4, 8)
}
