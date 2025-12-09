package com.example.dashboard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dashboard.data.AppDatabase
import com.example.dashboard.data.BackupData
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
import com.example.dashboard.data.SavedAddress
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import kotlin.math.round

///////////////////////////////////////////////////////////////////

// Classes pour mapper TON Json spécifique


/////////////////////////////////////////////////////////////////
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
    // Dans MaintenanceViewModel.kt

    fun saveItem(id: Int, name: String, intervalKm: Int, intervalMonths: Int, lastKm: Double) {
        viewModelScope.launch {
            // 1. On prépare l'objet Item (Le "Résumé")
            val itemToSave = MaintenanceItem(
                id = id,
                name = name,
                intervalKm = intervalKm,
                intervalMonths = intervalMonths,
                lastServiceKm = lastKm,
                lastServiceDate = System.currentTimeMillis() // Date de mise à jour
            )

            // 2. On sauvegarde l'Item et on RÉCUPÈRE son ID
            // (Si c'était une création, savedId sera le nouvel ID généré)
            val savedId = repository.saveMaintenanceItem(itemToSave)

            // 3. CORRECTION : On CRÉE une entrée dans l'historique (Log)
            // On enregistre : "J'ai fait cet entretien aujourd'hui à tel kilométrage"
            val newLog = MaintenanceLog(
                itemId = savedId.toInt(), // On lie ce log à l'item (clé étrangère)
                dateDone = System.currentTimeMillis(),
                kmDone = lastKm,
                comment = "Mise à jour manuelle", // Ou tu pourrais demander un commentaire dans la modale
                doneByOwner = true
            )

            // 4. On insère le log dans la BDD
            repository.insertLog(newLog)
        }
    }

    fun deleteItem(item: MaintenanceItem) {
        viewModelScope.launch { repository.deleteMaintenanceItem(item) }
    }

    // Dans MaintenanceViewModel.kt

    fun exportBackupJson(context: android.content.Context) {
        viewModelScope.launch {
            // 1. Récupérer les items
            val items = repository.maintenanceItems.first()

            // 2. Récupérer TOUS les logs
            val allLogs = mutableListOf<MaintenanceLog>()

            items.forEach { item ->
                // On va chercher l'historique pour CHAQUE item
                val itemLogs = repository.getLogsSync(item.id)
                allLogs.addAll(itemLogs)
            }

            // 3. Créer l'objet Backup
            val backup = BackupData(
                exportDate = System.currentTimeMillis(),
                items = items,
                logs = allLogs // <-- Si ça c'est vide, le JSON n'aura pas d'historique
            )

            // ... (Suite du code de conversion JSON et sauvegarde) ...
        }
    }
    // Génère une chaîne de caractères contenant tout le rapport HTML
    suspend fun generateHtmlReport(): String {
        val items = repository.maintenanceItems.first()
        val profile = repository.carProfile.firstOrNull() // On récupère le profil
        val sb = StringBuilder()

        // 1. HEADER HTML AVEC ENCODAGE FORCE
        sb.append("<!DOCTYPE html>")
        sb.append("<html><head><meta charset='UTF-8'>")
        sb.append("<style>")
        sb.append("body { font-family: sans-serif; padding: 20px; background-color: #fff; color: #333; }")
        sb.append("h1 { color: #2c3e50; border-bottom: 2px solid #3498db; padding-bottom: 10px; }")
        sb.append(".item { border: 1px solid #ddd; margin-bottom: 30px; padding: 15px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }")
        sb.append(".header { background-color: #f8f9fa; padding: 10px; font-weight: bold; font-size: 1.2em; color: #2c3e50; border-bottom: 1px solid #eee; }")
        sb.append("table { width: 100%; border-collapse: collapse; margin-top: 15px; }")
        sb.append("th, td { border: 1px solid #ddd; padding: 12px; text-align: center; }") // Centré c'est plus joli pour les chiffres
        sb.append("th { background-color: #34495e; color: white; }")
        sb.append(".late { color: #e74c3c; font-weight: bold; }") // Rouge pour retard
        sb.append(".early { color: #27ae60; font-weight: bold; }") // Vert pour avance
        sb.append(".neutral { color: #7f8c8d; font-style: italic; }")
        sb.append("</style></head><body>")

        sb.append("<h1>🚙 Carnet d'Entretien - Peugeot 206+</h1>")
        sb.append("<p>Exporté le : ${java.text.SimpleDateFormat("dd/MM/yyyy à HH:mm").format(java.util.Date())}</p>")

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
                // Colonnes demandées : Date | Km Prévu | Km Fait | Écart (Somme)
                sb.append("<table><tr><th>Date</th><th>Km Prévu</th><th>Km Fait</th><th>Écart / Bilan</th></tr>")

                // 2. TRI CROISSANT (Du plus vieux au plus récent)
                val sortedLogs = logs.sortedBy { it.kmDone }

                // Variable pour retenir le kilométrage de la ligne PRÉCÉDENTE
                var previousKmDone: Double? = null

                sortedLogs.forEachIndexed { index, log ->
                    val dateStr = SimpleDateFormat("dd/MM/yy").format(log.dateDone)
                    val kmFaitStr = "${log.kmDone.toInt()} km"

                    var kmPrevuStr = "-"
                    var ecartStr = ""
                    val targetKm: Double

                    if (index == 0) {
                        // C'est le tout premier log connu !
                        // On calcule par rapport à la "Naissance de la voiture" (0 km)
                        // On cherche le multiple de l'intervalle le plus proche
                        val multiple = round(log.kmDone / item.intervalKm)
                        targetKm = multiple * item.intervalKm

                        // Cas particulier : Si target est 0 (ex: fait à 5000km pour intervalle 20000)
                        // On peut soit dire "Cible 0" (Retard de 5000), soit "Cible 20000" (Avance de 15000)
                        // La logique mathématique reste la même.
                    } else {
                        // Ce n'est pas le premier log
                        // On calcule par rapport au précédent log + intervalle
                        targetKm = previousKmDone!! + item.intervalKm
                    }

                    kmPrevuStr = "${targetKm.toInt()} km"

                    // Calcul Ecart
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

                    // On met à jour le "précédent" pour le prochain tour de boucle
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

    fun importBackupJson(jsonString: String, context: android.content.Context) {
        viewModelScope.launch {
            try {
                val gson = com.google.gson.Gson()

                // 1. On parse avec la nouvelle structure
                val rootData = gson.fromJson(jsonString, JsonRoot::class.java)

                if (rootData.items == null) {
                    android.widget.Toast.makeText(context, "Erreur : Aucune donnée trouvée dans le JSON", android.widget.Toast.LENGTH_LONG).show()
                    return@launch
                }

                var itemsCount = 0
                var logsCount = 0

                // Format de date utilisé dans ton JSON (ex: 09/12/25)
                val dateFormat = java.text.SimpleDateFormat("dd/MM/yy", java.util.Locale.FRANCE)

                // 2. On parcourt les items
                rootData.items.forEach { jsonItem ->

                    // A. Création de l'Item
                    val itemToInsert = MaintenanceItem(
                        id = 0, // 0 pour forcer la création d'un nouvel ID
                        name = jsonItem.name,
                        intervalKm = jsonItem.intervalKm,
                        intervalMonths = 0, // Valeur par défaut si absente du JSON
                        lastServiceKm = jsonItem.lastServiceKm,
                        lastServiceDate = System.currentTimeMillis() // On met la date du jour par défaut
                    )

                    // On récupère le NOUVEL ID généré par la base
                    val newId = repository.saveMaintenanceItem(itemToInsert)
                    itemsCount++

                    // B. Traitement des Logs imbriqués (s'il y en a)
                    jsonItem.logs?.forEach { jsonLog ->
                        // Conversion de la date Texte -> Timestamp
                        val timestamp = try {
                            dateFormat.parse(jsonLog.date)?.time ?: System.currentTimeMillis()
                        } catch (e: Exception) {
                            System.currentTimeMillis()
                        }

                        val logToInsert = MaintenanceLog(
                            id = 0,
                            itemId = newId.toInt(), // On lie au nouvel ID parent
                            dateDone = timestamp,
                            kmDone = jsonLog.km,
                            comment = jsonLog.comment ?: "",
                            doneByOwner = true // Par défaut true, à adapter si ton JSON évolue
                        )

                        repository.insertLog(logToInsert)
                        logsCount++
                    }
                }

                android.widget.Toast.makeText(
                    context,
                    "Succès : $itemsCount entretiens et $logsCount historiques importés.",
                    android.widget.Toast.LENGTH_LONG
                ).show()

            } catch (e: Exception) {
                e.printStackTrace()
                android.util.Log.e("DEBUG", "Erreur Import : ${e.message}")
                android.widget.Toast.makeText(context, "Erreur structure JSON : ${e.message}", android.widget.Toast.LENGTH_LONG).show()
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