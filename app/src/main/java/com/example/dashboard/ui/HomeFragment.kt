package com.example.dashboard.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.dashboard.R // Vérifie que c'est bien ton R
import com.example.dashboard.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Navigation vers le Dashboard (Conduite)
        binding.btnDrive.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_dashboard)
        }

        // Navigation vers l'Entretien
        binding.btnMaintenance.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_maintenance)
        }

        // Ouverture Profil (Pour l'instant un simple message)
        binding.btnProfile.setOnClickListener {
            Toast.makeText(context, "Bientôt: Modification du profil", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}