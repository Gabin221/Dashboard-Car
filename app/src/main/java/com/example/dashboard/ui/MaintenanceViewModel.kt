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
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class MaintenanceViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: CarRepository

    // Enumération des tris possibles
    enum class SortOrder {
        NAME_ASC,       // Alphabétique A-Z
        NAME_DESC,      // Alphabétique Z-A
        URGENCY_ASC,    // Le plus urgent en premier (Reste le moins de KM)
        URGENCY_DESC    // Le moins urgent en premier
    }

    // On stocke le choix actuel (par défaut : Urgence)
    private val _currentSort = kotlinx.coroutines.flow.MutableStateFlow(SortOrder.URGENCY_ASC)

    // Fonction pour changer le tri depuis l'interface
    fun setSortOrder(order: SortOrder) {
        _currentSort.value = order
    }

    init {
        val db = AppDatabase.getDatabase(application)
        repository = CarRepository(db.carDao(), db.savedAddressDao())
    }

    // On met à jour la grosse pipeline de données
    val maintenanceListState = combine(
        repository.maintenanceItems,
        repository.carProfile,
        _currentSort // <--- On ajoute le tri dans le mixeur
    ) { items, profile, sortOrder ->
        val currentKm = profile?.totalMileage ?: 0.0

        // 1. On calcule tout comme avant
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

        // 2. On applique le tri sur la liste calculée
        when (sortOrder) {
            SortOrder.NAME_ASC -> uiList.sortedBy { it.item.name }
            SortOrder.NAME_DESC -> uiList.sortedByDescending { it.item.name }
            SortOrder.URGENCY_ASC -> uiList.sortedBy { it.remainingKm } // Petits KM restants en haut
            SortOrder.URGENCY_DESC -> uiList.sortedByDescending { it.remainingKm }
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

    // Génère une chaîne de caractères contenant tout le rapport HTML
    suspend fun generateHtmlReport(): String {
        val items = repository.maintenanceItems.first()
        val sb = StringBuilder()

        sb.append("<html><head><style>")
        sb.append("body { font-family: sans-serif; padding: 20px; }")
        sb.append("h1 { color: #333; }")
        sb.append(".item { border: 1px solid #ddd; margin-bottom: 20px; padding: 10px; border-radius: 8px; }")
        sb.append(".header { background-color: #f2f2f2; padding: 10px; font-weight: bold; }")
        sb.append("table { width: 100%; border-collapse: collapse; margin-top: 10px; }")
        sb.append("th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }")
        sb.append("th { background-color: #4CAF50; color: white; }")
        sb.append("</style></head><body>")

        sb.append("<h1>🚙 Carnet d'Entretien - Peugeot 206+</h1>")
        sb.append("<p>Exporté le : ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(java.util.Date())}</p>")

        items.forEach { item ->
            sb.append("<div class='item'>")
            sb.append("<div class='header'>🔧 ${item.name} (Intervalle: ${item.intervalKm} km)</div>")

            // Récupération des logs
            val logs = repository.getLogsSync(item.id)

            if (logs.isNotEmpty()) {
                sb.append("<table><tr><th>Date</th><th>Kilométrage</th><th>Commentaire</th></tr>")
                logs.sortedByDescending { it.dateDone }.forEach { log ->
                    val date = java.text.SimpleDateFormat("dd/MM/yy").format(log.dateDone)
                    sb.append("<tr>")
                    sb.append("<td>$date</td>")
                    sb.append("<td><b>${log.kmDone.toInt()} km</b></td>")
                    sb.append("<td>${log.comment}</td>")
                    sb.append("</tr>")
                }
                sb.append("</table>")
            } else {
                sb.append("<p><i>Aucun historique pour cet élément.</i></p>")
            }
            sb.append("</div>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    suspend fun generateJsonReport(): String {
        val items = repository.maintenanceItems.first()
        val exportDate = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(java.util.Date())

        // --- FIX STARTS HERE ---
        // Initialize an empty MutableMap first to avoid "Argument type mismatch"
        val report = mutableMapOf<String, Any>()

        report["meta"] = mapOf(
            "title" to "Carnet d'Entretien - Peugeot 206+",
            "exportDate" to exportDate,
            "vehicle" to "Peugeot 206+"
        )

        report["items"] = mutableListOf<Map<String, Any>>()
        // --- FIX ENDS HERE ---

        // Pour chaque item, on construit une entrée JSON
        items.forEach { item ->
            val logs = repository.getLogsSync(item.id)
            val itemLogs = logs.sortedByDescending { it.dateDone }.map { log ->
                mapOf(
                    "date" to java.text.SimpleDateFormat("dd/MM/yy").format(log.dateDone),
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
            // This cast will now work correctly
            (report["items"] as MutableList<Map<String, Any>>).add(itemEntry)
        }

        // Conversion en JSON (exemple avec Gson)
        return Gson().toJson(report)
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

    // Dans MaintenanceViewModel

    // Fonction pour tout supprimer
    fun deleteAllMaintenanceData() {
        viewModelScope.launch {
            // Attention : Si tes Logs sont en CASCADE, ils seront aussi supprimés (ce qui est logique pour un "Remplacer")
            repository.deleteAllItems() // Ajoute cette méthode dans ton Repository qui appelle le DAO
        }
    }

    // Une variable simple pour savoir si on a des données
    // On peut utiliser la valeur actuelle du Flow
    fun hasData(): Boolean {
        // On regarde si la liste actuelle (maintenanceListState) n'est pas vide
        return maintenanceListState.value.isNotEmpty()
    }

    fun updateFavoriteName(item: SavedAddress, newName: String) {
        // dao.update(item.copy(name = newName))
    }

    // Tes fonctions d'export/import (inchangées sur le principe, mais vérifie les propriétés)
    fun exportData(context: android.content.Context) { /* ... Ton code d'export ... */ }
}