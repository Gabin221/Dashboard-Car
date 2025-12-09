package com.example.dashboard.ui

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dashboard.data.AppDatabase
import com.example.dashboard.data.CarRepository
import com.example.dashboard.data.MaintenanceItem
import com.example.dashboard.data.MaintenanceLog
import com.example.dashboard.data.JsonRoot
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.example.dashboard.data.MaintenanceUiState
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.round

class MaintenanceViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: CarRepository
    enum class SortOrder {
        NAME_ASC,
        NAME_DESC,
        URGENCY_ASC,
        URGENCY_DESC
    }

    private val _currentSort = MutableStateFlow(SortOrder.URGENCY_ASC)

    fun setSortOrder(order: SortOrder) {
        _currentSort.value = order
    }

    init {
        val db = AppDatabase.getDatabase(application)
        repository = CarRepository(db.carDao())
    }

    val maintenanceListState = combine(
        repository.maintenanceItems,
        repository.carProfile,
        _currentSort
    ) { items, profile, sortOrder ->
        val currentKm = profile?.totalMileage ?: 0.0

        val uiList = items.map { item ->
            val distanceDriven = currentKm - item.lastServiceKm
            val remainingKm = item.intervalKm - distanceDriven
            val progressPercent = if (item.intervalKm > 0) {
                (distanceDriven / item.intervalKm * 100).toInt().coerceIn(0, 100)
            } else { 0 }

            MaintenanceUiState(
                item = item,
                currentCarKm = currentKm,
                remainingKm = remainingKm,
                progressPercent = progressPercent,
                statusColor = when {
                    remainingKm < 0 -> 0xFFFF5252.toInt()
                    remainingKm < item.warningThreshold -> 0xFFFFAB00.toInt()
                    else -> 0xFF4CAF50.toInt()
                }
            )
        }

        when (sortOrder) {
            SortOrder.NAME_ASC -> uiList.sortedBy { it.item.name }
            SortOrder.NAME_DESC -> uiList.sortedByDescending { it.item.name }
            SortOrder.URGENCY_ASC -> uiList.sortedBy { it.remainingKm }
            SortOrder.URGENCY_DESC -> uiList.sortedByDescending { it.remainingKm }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveItem(id: Int, name: String, intervalKm: Int, intervalMonths: Int, lastKm: Double) {
        viewModelScope.launch {
            val itemToSave = MaintenanceItem(
                id = id,
                name = name,
                intervalKm = intervalKm,
                intervalMonths = intervalMonths,
                lastServiceKm = lastKm,
                lastServiceDate = System.currentTimeMillis()
            )

            val savedId = repository.saveMaintenanceItem(itemToSave)

            val newLog = MaintenanceLog(
                itemId = savedId.toInt(),
                dateDone = System.currentTimeMillis(),
                kmDone = lastKm,
                comment = "Mise à jour manuelle",
                doneByOwner = true
            )

            repository.insertLog(newLog)
        }
    }

    fun deleteItem(item: MaintenanceItem) {
        viewModelScope.launch { repository.deleteMaintenanceItem(item) }
    }

    suspend fun generateHtmlReport(): String {
        val items = repository.maintenanceItems.first()
        val profile = repository.carProfile.firstOrNull()
        val sb = StringBuilder()

        sb.append("<!DOCTYPE html>")
        sb.append("<html><head><meta charset='UTF-8'>")
        sb.append("<style>")
        sb.append("body { font-family: sans-serif; padding: 20px; background-color: #fff; color: #333; }")
        sb.append("h1 { color: #2c3e50; border-bottom: 2px solid #3498db; padding-bottom: 10px; }")
        sb.append(".item { border: 1px solid #ddd; margin-bottom: 30px; padding: 15px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }")
        sb.append(".header { background-color: #f8f9fa; padding: 10px; font-weight: bold; font-size: 1.2em; color: #2c3e50; border-bottom: 1px solid #eee; }")
        sb.append("table { width: 100%; border-collapse: collapse; margin-top: 15px; }")
        sb.append("th, td { border: 1px solid #ddd; padding: 12px; text-align: center; }")
        sb.append("th { background-color: #34495e; color: white; }")
        sb.append(".late { color: #e74c3c; font-weight: bold; }")
        sb.append(".early { color: #27ae60; font-weight: bold; }")
        sb.append(".neutral { color: #7f8c8d; font-style: italic; }")
        sb.append("</style></head><body>")

        sb.append("<h1>🚙 Carnet d'Entretien</h1>")
        sb.append("<p>Exporté le : ${SimpleDateFormat("dd/MM/yyyy à HH:mm").format(Date())}</p>")

        if (profile != null) {
            sb.append("<div style='background:#ecf0f1; padding:15px; border-radius:5px; border-left: 5px solid #3498db; margin-bottom:20px;'>")
            sb.append("<h2>${profile.carModel}</h2>")
            sb.append("<p><b>Immatriculation :</b> ${profile.licensePlate}</p>")
            sb.append("<p><b>Kilométrage actuel :</b> ${profile.totalMileage.toInt()} km</p>")
            sb.append("<p><b>Carburant :</b> ${profile.fuelType}</p>")

            if (profile.histovecLink.isNotEmpty()) {
                sb.append("<p>📜 <a href='${profile.histovecLink}'>Voir le rapport officiel HistoVec</a></p>")
            }
            sb.append("</div>")
        }

        items.forEach { item ->
            sb.append("<div class='item'>")
            sb.append("<div class='header'>🔧 ${item.name} (Tous les ${item.intervalKm} km)</div>")

            val logs = repository.getLogsSync(item.id)

            if (logs.isNotEmpty()) {
                sb.append("<table><tr><th>Date</th><th>Km Prévu</th><th>Km Fait</th><th>Écart / Bilan</th></tr>")

                val sortedLogs = logs.sortedBy { it.kmDone }

                var previousKmDone: Double? = null

                sortedLogs.forEachIndexed { index, log ->
                    val dateStr = SimpleDateFormat("dd/MM/yy").format(log.dateDone)
                    val kmFaitStr = "${log.kmDone.toInt()} km"

                    var kmPrevuStr: String
                    var ecartStr: String
                    val targetKm: Double

                    if (index == 0) {
                        val multiple = round(log.kmDone / item.intervalKm)
                        targetKm = multiple * item.intervalKm
                    } else {
                        targetKm = previousKmDone!! + item.intervalKm
                    }

                    kmPrevuStr = "${targetKm.toInt()} km"

                    val diff = log.kmDone - targetKm

                    if (diff > 0) {
                        ecartStr = "<span class='late'>+${diff.toInt()} km</span>"
                    } else if (diff < 0) {
                        ecartStr = "<span class='early'>${diff.toInt()} km</span>"
                    } else {
                        ecartStr = "<span class='early'>${diff.toInt()} km</span>"
                    }

                    sb.append("<tr>")
                    sb.append("<td>$dateStr</td>")
                    sb.append("<td>$kmPrevuStr</td>")
                    sb.append("<td><b>$kmFaitStr</b></td>")
                    sb.append("<td>$ecartStr</td>")
                    sb.append("</tr>")

                    previousKmDone = log.kmDone
                }
                sb.append("</table>")
            } else {
                sb.append("<p><i>Aucun historique enregistré pour cet élément.</i></p>")
            }
            sb.append("</div>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }
    suspend fun generateJsonReport(): String {
        val items = repository.maintenanceItems.first()
        val exportDate = SimpleDateFormat("dd/MM/yyyy HH:mm").format(Date())

        val report = mutableMapOf<String, Any>()

        report["meta"] = mapOf(
            "title" to "Carnet d'Entretien - Peugeot 206+",
            "exportDate" to exportDate,
            "vehicle" to "Peugeot 206+"
        )

        report["items"] = mutableListOf<Map<String, Any>>()
        items.forEach { item ->
            val logs = repository.getLogsSync(item.id)
            val itemLogs = logs.sortedByDescending { it.dateDone }.map { log ->
                mapOf(
                    "date" to SimpleDateFormat("dd/MM/yy").format(log.dateDone),
                    "km" to log.kmDone.toInt(),
                    "comment" to (log.comment ?: "")
                )
            }

            val itemEntry = mutableMapOf(
                "name" to item.name,
                "intervalKm" to item.intervalKm,
                "lastServiceKm" to item.lastServiceKm.toInt(),
                "logs" to if (itemLogs.isNotEmpty()) itemLogs else listOf(mapOf("info" to "Aucun historique pour cet élément."))
            )
            (report["items"] as MutableList<Map<String, Any>>).add(itemEntry)
        }

        return Gson().toJson(report)
    }

    fun importBackupJson(jsonString: String, context: Context) {
        viewModelScope.launch {
            try {
                val gson = Gson()

                val rootData = gson.fromJson(jsonString, JsonRoot::class.java)

                if (rootData.items == null) {
                    Toast.makeText(context, "Erreur : Aucune donnée trouvée dans le JSON", Toast.LENGTH_LONG).show()
                    return@launch
                }

                var itemsCount = 0
                var logsCount = 0

                val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.FRANCE)

                rootData.items.forEach { jsonItem ->

                    val itemToInsert = MaintenanceItem(
                        id = 0,
                        name = jsonItem.name,
                        intervalKm = jsonItem.intervalKm,
                        intervalMonths = 0,
                        lastServiceKm = jsonItem.lastServiceKm,
                        lastServiceDate = System.currentTimeMillis()
                    )

                    val newId = repository.saveMaintenanceItem(itemToInsert)
                    itemsCount++

                    jsonItem.logs?.forEach { jsonLog ->
                        val timestamp = try {
                            dateFormat.parse(jsonLog.date)?.time ?: System.currentTimeMillis()
                        } catch (e: Exception) {
                            System.currentTimeMillis()
                        }

                        val logToInsert = MaintenanceLog(
                            id = 0,
                            itemId = newId.toInt(),
                            dateDone = timestamp,
                            kmDone = jsonLog.km,
                            comment = jsonLog.comment ?: "",
                            doneByOwner = true
                        )

                        repository.insertLog(logToInsert)
                        logsCount++
                    }
                }

                Toast.makeText(
                    context,
                    "Succès : $itemsCount entretiens et $logsCount historiques importés.",
                    Toast.LENGTH_LONG
                ).show()

            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("DEBUG", "Erreur Import : ${e.message}")
                Toast.makeText(context, "Erreur structure JSON : ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun deleteAllMaintenanceData() {
        viewModelScope.launch {
            repository.deleteAllItems()
        }
    }

    fun hasData(): Boolean {
        return maintenanceListState.value.isNotEmpty()
    }
}