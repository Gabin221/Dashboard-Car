package com.example.dashboard.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.dashboard.databinding.DialogProfileBinding
import kotlinx.coroutines.launch

class ProfileDialogFragment : DialogFragment() {

    private var _binding: DialogProfileBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ProfileViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Charger les données existantes
        lifecycleScope.launch {
            val profile = viewModel.getCurrentProfile()
            if (profile != null) {
                binding.etCarModel.setText(profile.carModel)
                binding.etTotalKm.setText(profile.totalMileage.toString())
                // Pour le spinner, c'est plus chiant à pré-sélectionner, on laisse par défaut pour l'instant
            }
        }

        binding.btnSaveProfile.setOnClickListener {
            val model = binding.etCarModel.text.toString()
            val km = binding.etTotalKm.text.toString().toDoubleOrNull() ?: 0.0
            val fuel = binding.spFuel.selectedItem.toString()

            viewModel.saveProfile(model, km, fuel)
            Toast.makeText(context, "Profil mis à jour !", Toast.LENGTH_SHORT).show()
            dismiss() // Ferme la fenêtre
        }
    }

    // Pour que la fenêtre soit jolie (largeur)
    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}