package com.example.dashboard.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.dashboard.R
import com.example.dashboard.databinding.FragmentMaintenanceBinding
import kotlinx.coroutines.launch

class MaintenanceFragment : Fragment() {

    private var _binding: FragmentMaintenanceBinding? = null

    private val openFileLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                val inputStream = requireContext().contentResolver.openInputStream(it)
                val jsonString = inputStream?.bufferedReader().use { reader -> reader?.readText() }

                if (jsonString != null) {
                    if (viewModel.hasData()) {
                        showImportConflictDialog(jsonString)
                    } else {
                        viewModel.importBackupJson(jsonString, requireContext())
                        Toast.makeText(context, "Import réussi !", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Erreur lecture : ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val saveReportLauncherHtml = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/html")) { uri ->
        uri?.let { destinationUri ->
            lifecycleScope.launch {
                try {
                    val htmlContent = viewModel.generateHtmlReport()

                    requireContext().contentResolver.openOutputStream(destinationUri)?.use { output ->
                        output.write(htmlContent.toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(context, "Rapport enregistré !", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val saveReportLauncherJson = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { destinationUri ->
            lifecycleScope.launch {
                try {
                    val jsonContent = viewModel.generateJsonReport()
                    requireContext().contentResolver.openOutputStream(destinationUri)?.use { output ->
                        output.write(jsonContent.toByteArray())
                    }
                    Toast.makeText(
                        context,
                        "Rapport JSON enregistré avec succès !",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        "Erreur sauvegarde: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }


    private val binding get() = _binding!!

    private val viewModel: MaintenanceViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMaintenanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = MaintenanceAdapter { selectedState ->
            showMaintenanceDialog(selectedState.item)
        }

        binding.rvMaintenance.adapter = adapter
        binding.rvMaintenance.layoutManager = LinearLayoutManager(context)

        lifecycleScope.launch {
            viewModel.maintenanceListState.collect { list ->
                adapter.submitList(list)
            }
        }

        binding.fabAdd.setOnClickListener {
            showMaintenanceDialog(null)
        }

        binding.toolbarMaintenance.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_sort -> {
                    showSortDialog()
                    true
                }
                R.id.action_import -> {
                    openFileLauncher.launch(arrayOf("*/*"))
                    true
                }
                R.id.action_export_html -> {
                    saveReportLauncherHtml.launch("Rapport_Entretien.html")
                    true
                }
                R.id.action_export_json -> {
                    saveReportLauncherJson.launch("data.json")
                    true
                }
                R.id.action_delete_all -> {
                    showDeleteAllConfirmation()
                    true
                }
                else -> false
            }
        }
    }

    private fun showSortDialog() {
        val options = arrayOf("Urgence (Plus pressé)", "Urgence (Moins pressé)", "Nom (A-Z)")
        AlertDialog.Builder(requireContext())
            .setTitle("Trier par...")
            .setItems(options) { _, which ->
                val sort = when (which) {
                    0 -> MaintenanceViewModel.SortOrder.URGENCY_ASC
                    1 -> MaintenanceViewModel.SortOrder.URGENCY_DESC
                    2 -> MaintenanceViewModel.SortOrder.NAME_ASC
                    else -> MaintenanceViewModel.SortOrder.URGENCY_ASC
                }
                viewModel.setSortOrder(sort)
            }
            .show()
    }

    private fun showDeleteAllConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Tout effacer ?")
            .setMessage("Attention, cela supprimera tous les entretiens et l'historique.")
            .setPositiveButton("Oui") { _, _ -> viewModel.deleteAllMaintenanceData() }
            .setNegativeButton("Non", null)
            .show()
    }

    private fun showImportConflictDialog(jsonString: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Base de données non vide")
            .setMessage("Voulez-vous ajouter ces données à la suite (Fusionner) ou tout effacer avant (Remplacer) ?")
            .setPositiveButton("Fusionner") { _, _ ->
                viewModel.importBackupJson(jsonString, requireContext())
                Toast.makeText(context, "Données ajoutées !", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Remplacer") { _, _ ->
                viewModel.deleteAllMaintenanceData()
                lifecycleScope.launch {
                    viewModel.deleteAllMaintenanceData()
                    kotlinx.coroutines.delay(200)
                    viewModel.importBackupJson(jsonString, requireContext())
                    Toast.makeText(context, "Base remplacée !", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Annuler", null)
            .show()
    }

    private fun showMaintenanceDialog(itemToEdit: com.example.dashboard.data.MaintenanceItem?) {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val inputName = EditText(context).apply {
            hint = "Nom (ex: Pneus)"
            setText(itemToEdit?.name ?: "")
        }
        val inputInterval = EditText(context).apply {
            hint = "Intervalle KM"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(itemToEdit?.intervalKm?.toString() ?: "")
        }
        val inputLastKm = EditText(context).apply {
            hint = "Fait à quel KM total ?"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(itemToEdit?.lastServiceKm?.toString() ?: "")
        }

        layout.addView(inputName)
        layout.addView(inputInterval)
        layout.addView(inputLastKm)

        val title = if (itemToEdit == null) "Nouvel Entretien" else "Modifier Entretien"

        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton("Enregistrer") { _, _ ->
                val name = inputName.text.toString().trim()
                val intervalStr = inputInterval.text.toString().trim()
                val lastKmStr = inputLastKm.text.toString().trim()

                if (name.isEmpty()) {
                    Toast.makeText(context, "Il faut un nom !", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val interval = intervalStr.toIntOrNull() ?: 0
                if (interval <= 0) {
                    Toast.makeText(context, "L'intervalle doit être supérieur à 0", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val lastKm = lastKmStr.toDoubleOrNull() ?: 0.0

                val id = itemToEdit?.id ?: 0
                val months = 0
                viewModel.saveItem(id, name, interval, months, lastKm)
            }
            .setNegativeButton("Annuler", null)

        if (itemToEdit != null) {
            builder.setNeutralButton("Supprimer") { _, _ ->
                viewModel.deleteItem(itemToEdit)
                Toast.makeText(context, "Supprimé !", Toast.LENGTH_SHORT).show()
            }
        }

        builder.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}