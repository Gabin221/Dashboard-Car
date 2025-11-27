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
import kotlin.random.Random

// use/your/value

object ObdManager {
    // UUID Standard pour les périphériques Série (ELM327 & Freematics)
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    // Pour tester sans voiture (mettre à false quand tu vas dans la 206)
    var isMockMode = false

    // Données observables par l'interface
    private val _currentRpm = MutableStateFlow(0)
    val currentRpm: StateFlow<Int> = _currentRpm

    private val _currentSpeed = MutableStateFlow(0)
    val currentSpeed: StateFlow<Int> = _currentSpeed

    private val _connectionStatus = MutableStateFlow("Déconnecté")
    val connectionStatus: StateFlow<String> = _connectionStatus

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

                // Initialisation basique ELM327
                // ATZ = Reset, ATSP0 = Protocole Auto, ATE0 = Echo Off (important pour simplifier le parsing)
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
            while (true) { // Boucle infinie tant que l'app tourne
                if (isMockMode) {
                    // --- SIMULATION ---
                    // On fait varier la vitesse et le régime aléatoirement
                    val fakeRpm = (800..3000).random()
                    val fakeSpeed = (0..130).random()

                    _currentRpm.emit(fakeRpm)
                    _currentSpeed.emit(fakeSpeed)
                    delay(1000) // Mise à jour chaque seconde
                } else {
                    // --- RÉEL (ELM327) ---
                    if (socket?.isConnected == true) {
                        try {
                            // 1. Lire RPM (PID 010C)
                            val rpmRaw = sendAndReceive("010C")
                            val rpmVal = parseRpm(rpmRaw)
                            if (rpmVal != -1) _currentRpm.emit(rpmVal)

                            // 2. Lire Vitesse (PID 010D)
                            val speedRaw = sendAndReceive("010D")
                            val speedVal = parseSpeed(speedRaw)
                            if (speedVal != -1) _currentSpeed.emit(speedVal)

                        } catch (e: Exception) {
                            Log.e("OBD", "Erreur lecture boucle", e)
                            break // Sortir de la boucle si déconnecté
                        }
                    } else {
                        break // Sortir si socket fermé
                    }
                }
            }
        }
    }

    private fun sendCommand(cmd: String) {
        outputStream?.write((cmd + "\r").toByteArray())
        outputStream?.flush()
        readResponse() // On vide le buffer
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
        // Lecture basique (à améliorer pour la prod car le ELM peut répondre en plusieurs morceaux)
        // Ici on suppose que la réponse arrive vite.
        try {
            Thread.sleep(100) // Petit délai pour laisser le ELM répondre
            if (inputStream?.available()!! > 0) {
                bytes = inputStream!!.read(buffer)
                sb.append(String(buffer, 0, bytes))
            }
        } catch (e: Exception) { }

        val raw = sb.toString().trim()
        Log.d("OBD_RAW", "Reçu: $raw") // Regarde tes logs (Logcat) pour voir ce que la 206 répond !
        return raw
    }

    // --- LOGIQUE DE PARSING ---

    // Réponse typique ELM: "41 0C 1A F8" (Hexadécimal)
    // 41 = Réponse au mode 01
    // 0C = PID demandé
    // 1A = A (Premier octet de donnée)
    // F8 = B (Deuxième octet de donnée)
    private fun parseRpm(raw: String): Int {
        val clean = raw.replace(" ", "").replace(">", "").trim()
        // Vérifie si la réponse commence bien par 410C
        if (clean.contains("410C")) {
            try {
                val dataPart = clean.substringAfter("410C")
                if (dataPart.length >= 4) {
                    val aHex = dataPart.substring(0, 2)
                    val bHex = dataPart.substring(2, 4)
                    val a = Integer.parseInt(aHex, 16)
                    val b = Integer.parseInt(bHex, 16)
                    // Formule Standard OBD: ((A*256)+B)/4
                    return ((a * 256) + b) / 4
                }
            } catch (e: Exception) { Log.e("OBD", "Erreur parsing RPM: $raw") }
        }
        return -1 // Erreur
    }

    private fun parseSpeed(raw: String): Int {
        val clean = raw.replace(" ", "").replace(">", "").trim()
        // Vérifie si la réponse commence bien par 410D
        if (clean.contains("410D")) {
            try {
                val dataPart = clean.substringAfter("410D")
                if (dataPart.length >= 2) {
                    val aHex = dataPart.substring(0, 2)
                    // Formule Standard OBD: A km/h
                    return Integer.parseInt(aHex, 16)
                }
            } catch (e: Exception) { Log.e("OBD", "Erreur parsing Speed: $raw") }
        }
        return -1
    }
}