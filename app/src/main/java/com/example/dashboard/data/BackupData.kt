package com.example.dashboard.data

// Une classe qui contient TOUT pour faciliter l'export JSON
data class BackupData(
    val exportDate: Long,
    val items: List<MaintenanceItem>,
    val logs: List<MaintenanceLog>
)