package com.example.dogfood

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
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

    // 수신 루프는 연결 동안 계속 블로킹되므로 송신 전용 실행기와 반드시 분리합니다.
    private val connectionExecutor = Executors.newSingleThreadExecutor()
    private val sendExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var socket: BluetoothSocket? = null
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
        connectionExecutor.execute {
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

                // 실제 Bluetooth SPP에서는 18글자 패킷이 나뉘어 들어올 수 있으므로
                // D를 시작점으로 찾아 완전한 패킷이 될 때까지 버퍼링합니다.
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
            if (socket === activeSocket) {
                connected = false
                closeQuietly()
                onConnectionChanged(false, "연결 종료")
            }
        }
    }

    fun send(text: String) {
        if (!connected) return
        sendExecutor.execute {
            try {
                socket?.outputStream?.apply {
                    write(text.toByteArray(Charsets.UTF_8))
                    flush()
                }
            } catch (_: IOException) {
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
        val current = socket
        socket = null
        try { current?.close() } catch (_: Exception) {}
    }

    fun shutdown() {
        disconnect()
        connectionExecutor.shutdownNow()
        sendExecutor.shutdownNow()
    }
}
