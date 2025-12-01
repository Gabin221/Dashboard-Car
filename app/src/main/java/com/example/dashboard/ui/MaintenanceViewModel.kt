package com.example.dashboard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dashboard.data.AppDatabase
import com.example.dashboard.data.BackupData
import com.example.dashboard.data.CarRepository
import com.example.dashboard.data.MaintenanceItem
import com.example.dashboard.data.MaintenanceLog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.example.dashboard.data.MaintenanceUiState
import com.example.dashboard.data.SavedAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class MaintenanceViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: CarRepository

    init {
        val db = AppDatabase.getDatabase(application)
        repository = CarRepository(db.carDao())
    }

    val maintenanceListState = combine(
        repository.maintenanceItems,
        repository.carProfile
    ) { items, profile ->
        val currentKm = profile?.totalMileage ?: 0.0

        items.map { item ->
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Mise à jour pour inclure les Mois
    fun saveItem(id: Int, name: String, intervalKm: Int, intervalMonths: Int, lastKm: Double) {
        viewModelScope.launch {
            val item = MaintenanceItem(
                id = id,
                name = name,
                intervalKm = intervalKm,
                intervalMonths = intervalMonths,
                lastServiceKm = lastKm,
                lastServiceDate = System.currentTimeMillis()
            )
            repository.saveMaintenanceItem(item)
        }
    }

    fun deleteItem(item: MaintenanceItem) {
        viewModelScope.launch { repository.deleteMaintenanceItem(item) }
    }

    // Dans MaintenanceViewModel.kt

    fun exportBackupJson(context: android.content.Context) {
        viewModelScope.launch {
            // 1. Récupérer toutes les données
            val items = repository.maintenanceItems.first()
            // Pour récupérer TOUS les logs d'un coup, il faudrait une méthode getAllLogs() dans le DAO
            // Sinon on boucle (moins performant mais OK pour petite BDD)
            val allLogs = mutableListOf<MaintenanceLog>()
            items.forEach { item ->
                allLogs.addAll(repository.getLogsSync(item.id))
            }

            val backup = BackupData(
                exportDate = System.currentTimeMillis(),
                items = items,
                logs = allLogs
            )

            // 2. Convertir en JSON
            val gson = com.google.gson.Gson()
            val jsonString = gson.toJson(backup)

            // 3. Sauvegarder dans un fichier et partager
            // On écrit dans le cache pour pouvoir l'envoyer
            val file = java.io.File(context.cacheDir, "backup_206_plus.json")
            file.writeText(jsonString)

            // Partage via Android Sharesheet
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider", // Nécessite un setup dans le Manifest !
                file
            )

            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Sauvegarder la BDD"))
        }
    }

    fun importBackupJson(jsonString: String) {
        viewModelScope.launch {
            try {
                val gson = com.google.gson.Gson()
                val backup = gson.fromJson(jsonString, BackupData::class.java)

                // On vide et on remplace ? Ou on ajoute ?
                // Pour une restauration, souvent on veut tout remettre à plat.
                // Attention aux IDs qui pourraient changer.

                backup.items.forEach { item ->
                    // On recrée l'item (ID 0 pour forcer insert ou on garde l'ID si on veut restaurer exact)
                    // Le mieux est de réinsérer proprement
                    repository.saveMaintenanceItem(item)

                    // Et ses logs associés
                    val itemLogs = backup.logs.filter { it.itemId == item.id }
                    itemLogs.forEach { log ->
                        repository.insertLog(log) // Faudra ajouter insertLog dans le repo
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun exportPdfReport(context: android.content.Context) {
        viewModelScope.launch {
            val items = repository.maintenanceItems.first()

            // 1. Génération du HTML
            val sb = StringBuilder()
            sb.append("<html><body>")
            sb.append("<h1>🚙 Carnet d'Entretien - Peugeot 206+</h1>")
            sb.append("<p>Date du rapport : ${java.text.SimpleDateFormat("dd/MM/yyyy").format(java.util.Date())}</p>")
            sb.append("<hr>")

            items.forEach { item ->
                sb.append("<h3>🔧 ${item.name}</h3>")
                sb.append("<ul>")
                sb.append("<li><b>Intervalle :</b> ${item.intervalKm} km</li>")
                sb.append("<li><b>Dernier fait à :</b> ${item.lastServiceKm.toInt()} km</li>")

                // Calcul rapide
                val logs = repository.getLogsSync(item.id)
                if (logs.isNotEmpty()) {
                    sb.append("</ul>")
                    sb.append("<table border='1' style='border-collapse: collapse; width: 100%;'>")
                    sb.append("<tr style='background-color: #f2f2f2;'><th>Date</th><th>KM</th><th>Commentaire</th></tr>")
                    logs.sortedByDescending { it.dateDone }.forEach { log ->
                        val date = java.text.SimpleDateFormat("dd/MM/yy").format(log.dateDone)
                        sb.append("<tr>")
                        sb.append("<td style='padding: 8px;'>$date</td>")
                        sb.append("<td style='padding: 8px;'>${log.kmDone.toInt()} km</td>")
                        sb.append("<td style='padding: 8px;'>${log.comment}</td>")
                        sb.append("</tr>")
                    }
                    sb.append("</table>")
                } else {
                    sb.append("<li><i>Aucun historique enregistré</i></li></ul>")
                }
                sb.append("<br>")
            }
            sb.append("</body></html>")

            // 2. Impression PDF via le système Android
            withContext(Dispatchers.Main) {
                val printManager = context.getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager
                val webView = android.webkit.WebView(context)
                webView.loadDataWithBaseURL(null, sb.toString(), "text/html", "UTF-8", null)

                // On doit attendre que la WebView ait fini de "rendre" le HTML pour imprimer
                webView.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView, url: String) {
                        val printAdapter = view.createPrintDocumentAdapter("Rapport_Entretien_206")
                        printManager.print("Rapport Entretien", printAdapter, android.print.PrintAttributes.Builder().build())
                    }
                }
            }
        }
    }

    fun updateFavoriteName(item: SavedAddress, newName: String) {
        // dao.update(item.copy(name = newName))
    }

    // Tes fonctions d'export/import (inchangées sur le principe, mais vérifie les propriétés)
    fun exportData(context: android.content.Context) { /* ... Ton code d'export ... */ }
}