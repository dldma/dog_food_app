package com.example.dogfood

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

class BluetoothController(
    context: Context,
    private val onPacket: (DogState) -> Unit,
    private val onConnectionChanged: (Boolean, String) -> Unit,
) {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter

    private val executor = Executors.newSingleThreadExecutor()
    private var socket: BluetoothSocket? = null
    @Volatile private var connected = false

    companion object {
        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    fun isBluetoothAvailable(): Boolean = adapter != null
    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true
    fun isConnected(): Boolean = connected

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> =
        adapter?.bondedDevices?.sortedBy { it.name ?: it.address } ?: emptyList()

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        disconnect()
        executor.execute {
            try {
                adapter?.cancelDiscovery()
                val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                newSocket.connect()
                socket = newSocket
                connected = true
                onConnectionChanged(true, device.name ?: device.address)
                readLoop(newSocket)
            } catch (e: Exception) {
                connected = false
                closeQuietly()
                onConnectionChanged(false, e.message ?: "블루투스 연결 실패")
            }
        }
    }

    private fun readLoop(activeSocket: BluetoothSocket) {
        val input = activeSocket.inputStream
        val temp = ByteArray(256)
        val buffer = StringBuilder()

        try {
            while (connected) {
                val count = input.read(temp)
                if (count <= 0) break
                buffer.append(String(temp, 0, count, Charsets.UTF_8))

                // 원본은 18바이트일 때만 해석했지만, 실제 블루투스는 패킷이
                // 나뉘어 도착할 수 있으므로 버퍼링해서 D로 시작하는 18글자를 찾습니다.
                while (true) {
                    val start = buffer.indexOf("D")
                    if (start < 0) {
                        if (buffer.length > 256) buffer.clear()
                        break
                    }
                    if (start > 0) buffer.delete(0, start)
                    if (buffer.length < DogFoodProtocol.PACKET_LENGTH) break

                    val candidate = buffer.substring(0, DogFoodProtocol.PACKET_LENGTH)
                    val state = DogFoodProtocol.parse(candidate)
                    if (state != null) {
                        onPacket(state)
                        buffer.delete(0, DogFoodProtocol.PACKET_LENGTH)
                    } else {
                        buffer.deleteCharAt(0)
                    }
                }
            }
        } catch (_: IOException) {
        } finally {
            connected = false
            closeQuietly()
            onConnectionChanged(false, "연결 종료")
        }
    }

    fun send(text: String) {
        executor.execute {
            try {
                socket?.outputStream?.apply {
                    write(text.toByteArray(Charsets.UTF_8))
                    flush()
                }
            } catch (e: IOException) {
                connected = false
                closeQuietly()
                onConnectionChanged(false, "전송 실패")
            }
        }
    }

    fun disconnect() {
        connected = false
        closeQuietly()
    }

    private fun closeQuietly() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    fun shutdown() {
        disconnect()
        executor.shutdownNow()
    }
}
