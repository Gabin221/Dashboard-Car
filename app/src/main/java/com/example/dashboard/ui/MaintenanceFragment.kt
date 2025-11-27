package com.example.dashboard.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.dashboard.databinding.FragmentMaintenanceBinding
import kotlinx.coroutines.launch

class MaintenanceFragment : Fragment() {

    private var _binding: FragmentMaintenanceBinding? = null
    private val binding get() = _binding!!

    // On lie le ViewModel
    private val viewModel: MaintenanceViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMaintenanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    // Dans onViewCreated
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // On passe la fonction qui gère le clic
        val adapter = MaintenanceAdapter { selectedState ->
            showMaintenanceDialog(selectedState.item) // On ouvre le dialogue avec l'item existant
        }

        binding.rvMaintenance.adapter = adapter
        binding.rvMaintenance.layoutManager = LinearLayoutManager(context)

        // ... observers ...

        binding.fabAdd.setOnClickListener {
            showMaintenanceDialog(null) // null = On crée un nouveau
        }
    }

    // Fonction unifiée pour Créer OU Modifier
    private fun showMaintenanceDialog(itemToEdit: com.example.dashboard.data.MaintenanceItem?) {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val inputName = EditText(context).apply {
            hint = "Nom (ex: Pneus)"
            setText(itemToEdit?.name ?: "Pneus") // Pré-remplir si modif
        }
        val inputInterval = EditText(context).apply {
            hint = "Intervalle KM"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(itemToEdit?.intervalKm?.toString() ?: "10000")
        }
        val inputLastKm = EditText(context).apply {
            hint = "Fait à quel KM total ?"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(itemToEdit?.lastServiceKm?.toString() ?: "110000")
        }

        layout.addView(inputName)
        layout.addView(inputInterval)
        layout.addView(inputLastKm)

        val title = if (itemToEdit == null) "Nouvel Entretien" else "Modifier Entretien"

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton("Enregistrer") { _, _ ->
                // Récupération des valeurs
                val name = inputName.text.toString().trim()
                val intervalStr = inputInterval.text.toString().trim()
                val lastKmStr = inputLastKm.text.toString().trim()

                // --- CORRECTION : Validation des données ---
                if (name.isEmpty()) {
                    android.widget.Toast.makeText(context, "Il faut un nom !", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val interval = intervalStr.toIntOrNull() ?: 0
                if (interval <= 0) {
                    android.widget.Toast.makeText(context, "L'intervalle doit être supérieur à 0", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val lastKm = lastKmStr.toDoubleOrNull() ?: 0.0

                // Si tout est bon, on sauvegarde
                val id = itemToEdit?.id ?: 0
                viewModel.saveItem(id, name, interval, lastKm)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}