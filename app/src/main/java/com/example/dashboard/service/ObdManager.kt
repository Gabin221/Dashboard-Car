package com.example.dashboard.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

object ObdManager {
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    var isMockMode = true

    private val _currentRpm = MutableStateFlow(0)
    val currentRpm: StateFlow<Int> = _currentRpm

    private val _currentSpeed = MutableStateFlow(0)
    val currentSpeed: StateFlow<Int> = _currentSpeed
    private val _currentData1 = MutableStateFlow(0)
    val currentData1: StateFlow<Int> = _currentData1
    private val _currentData2 = MutableStateFlow(0)
    val currentData2: StateFlow<Int> = _currentData2

    private val _connectionStatus = MutableStateFlow("Déconnecté")

    @SuppressLint("MissingPermission")
    suspend fun connect(deviceAddress: String, adapter: BluetoothAdapter): Boolean {
        if (isMockMode) {
            _connectionStatus.value = "Mode Simulation"
            return true
        }

        return withContext(Dispatchers.IO) {
            try {
                _connectionStatus.value = "Connexion..."
                val device = adapter.getRemoteDevice(deviceAddress)
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket?.connect()

                inputStream = socket?.inputStream
                outputStream = socket?.outputStream

                sendCommand("ATZ")
                delay(1000)
                sendCommand("ATE0")
                delay(200)
                sendCommand("ATSP0")
                delay(500)

                _connectionStatus.value = "Connecté à la 206"
                true
            } catch (e: IOException) {
                Log.e("OBD", "Erreur connexion", e)
                _connectionStatus.value = "Erreur: ${e.message}"
                false
            }
        }
    }

    suspend fun startDataLoop() {
        withContext(Dispatchers.IO) {
            while (true) {
                if (isMockMode) {
                    val fakeRpm = (800..3000).random()
                    val fakeSpeed = (0..130).random()
                    val fakeData1 = (0..10).random()
                    val fakeData2 = (0..10).random()

                    _currentRpm.emit(fakeRpm)
                    _currentSpeed.emit(fakeSpeed)
                    _currentData1.emit(fakeData1)
                    _currentData2.emit(fakeData2)
                    delay(1000)
                } else {
                    if (socket?.isConnected == true) {
                        try {
                            val rpmRaw = sendAndReceive("010C")
                            val rpmVal = parseRpm(rpmRaw)
                            if (rpmVal != -1) _currentRpm.emit(rpmVal)

                            val speedRaw = sendAndReceive("010D")
                            val speedVal = parseSpeed(speedRaw)
                            if (speedVal != -1) _currentSpeed.emit(speedVal)

                        } catch (e: Exception) {
                            Log.e("OBD", "Erreur lecture boucle", e)
                            break
                        }
                    } else {
                        break
                    }
                }
            }
        }
    }

    private fun sendCommand(cmd: String) {
        outputStream?.write((cmd + "\r").toByteArray())
        outputStream?.flush()
        readResponse()
    }

    private fun sendAndReceive(cmd: String): String {
        outputStream?.write((cmd + "\r").toByteArray())
        outputStream?.flush()
        return readResponse()
    }

    private fun readResponse(): String {
        val buffer = ByteArray(1024)
        var bytes: Int
        val sb = StringBuilder()
        try {
            Thread.sleep(100)
            if (inputStream?.available()!! > 0) {
                bytes = inputStream!!.read(buffer)
                sb.append(String(buffer, 0, bytes))
            }
        } catch (e: Exception) { }

        val raw = sb.toString().trim()
        Log.d("OBD_RAW", "Reçu: $raw")
        return raw
    }

    private fun parseRpm(raw: String): Int {
        val clean = raw.replace(" ", "").replace(">", "").trim()
        if (clean.contains("410C")) {
            try {
                val dataPart = clean.substringAfter("410C")
                if (dataPart.length >= 4) {
                    val aHex = dataPart.substring(0, 2)
                    val bHex = dataPart.substring(2, 4)
                    val a = Integer.parseInt(aHex, 16)
                    val b = Integer.parseInt(bHex, 16)
                    return ((a * 256) + b) / 4
                }
            } catch (e: Exception) { Log.e("OBD", "Erreur parsing RPM: $raw") }
        }
        return -1
    }

    private fun parseSpeed(raw: String): Int {
        val clean = raw.replace(" ", "").replace(">", "").trim()
        if (clean.contains("410D")) {
            try {
                val dataPart = clean.substringAfter("410D")
                if (dataPart.length >= 2) {
                    val aHex = dataPart.substring(0, 2)
                    return Integer.parseInt(aHex, 16)
                }
            } catch (e: Exception) {
                Log.e("OBD", "Erreur parsing Speed: $raw")
            }
        }
        return -1
    }
}