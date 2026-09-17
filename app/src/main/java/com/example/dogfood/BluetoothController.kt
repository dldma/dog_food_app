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
    private val onDeviceEvent: (DeviceFeedEvent) -> Unit = {},
    private val onConnectionChanged: (Boolean, String) -> Unit,
) {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter

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
                consumeBuffer(buffer)
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

    // Dxxxxxxxxxxxxxxxxx : 기존 18글자 상태 패킷
    // !L,...\n            : 생활 급식 실행 이벤트
    // 두 형식이 같은 SPP 스트림에 섞여 와도 안전하게 분리한다.
    private fun consumeBuffer(buffer: StringBuilder) {
        while (buffer.isNotEmpty()) {
            val dIndex = buffer.indexOf("D")
            val eventIndex = buffer.indexOf("!")
            val start = when {
                dIndex < 0 -> eventIndex
                eventIndex < 0 -> dIndex
                else -> minOf(dIndex, eventIndex)
            }

            if (start < 0) {
                if (buffer.length > 512) buffer.clear()
                return
            }
            if (start > 0) buffer.delete(0, start)

            when (buffer.first()) {
                'D' -> {
                    if (buffer.length < DogFoodProtocol.PACKET_LENGTH) return
                    val candidate = buffer.substring(0, DogFoodProtocol.PACKET_LENGTH)
                    val state = DogFoodProtocol.parse(candidate)
                    if (state != null) {
                        onPacket(state)
                        buffer.delete(0, DogFoodProtocol.PACKET_LENGTH)
                    } else {
                        buffer.deleteCharAt(0)
                    }
                }
                '!' -> {
                    val newline = buffer.indexOf("\n")
                    val carriage = buffer.indexOf("\r")
                    val end = listOf(newline, carriage).filter { it >= 0 }.minOrNull() ?: -1
                    if (end < 0) {
                        if (buffer.length > 128) buffer.deleteCharAt(0)
                        return
                    }
                    val line = buffer.substring(0, end)
                    DogFoodProtocol.parseDeviceEvent(line)?.let(onDeviceEvent)
                    var remove = end + 1
                    while (remove < buffer.length && (buffer[remove] == '\n' || buffer[remove] == '\r')) remove++
                    buffer.delete(0, remove)
                }
                else -> buffer.deleteCharAt(0)
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
