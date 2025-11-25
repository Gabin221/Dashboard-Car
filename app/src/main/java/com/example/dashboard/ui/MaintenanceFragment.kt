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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup Liste
        val adapter = MaintenanceAdapter()
        binding.rvMaintenance.adapter = adapter
        binding.rvMaintenance.layoutManager = LinearLayoutManager(context)

        // Observer les données
        lifecycleScope.launch {
            viewModel.maintenanceListState.collect { list ->
                adapter.submitList(list)
            }
        }

        // Clic sur le bouton +
        binding.fabAdd.setOnClickListener {
            showAddDialog()
        }
    }

    // Petite modale rapide pour ajouter un élément (En attendant un écran dédié)
    private fun showAddDialog() {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val inputName = EditText(context).apply { hint = "Nom (ex: Pneus)" }
        val inputInterval = EditText(context).apply {
            hint = "Intervalle KM (ex: 40000)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val inputLastKm = EditText(context).apply {
            hint = "Fait à quel KM total ? (ex: 120000)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        layout.addView(inputName)
        layout.addView(inputInterval)
        layout.addView(inputLastKm)

        AlertDialog.Builder(context)
            .setTitle("Nouvel Entretien")
            .setView(layout)
            .setPositiveButton("Ajouter") { _, _ ->
                val name = inputName.text.toString()
                val interval = inputInterval.text.toString().toIntOrNull() ?: 30000
                val lastKm = inputLastKm.text.toString().toDoubleOrNull() ?: 0.0

                // Sauvegarde via ViewModel
                viewModel.addOrUpdateItem(name, interval, lastKm, warning = 2000)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}