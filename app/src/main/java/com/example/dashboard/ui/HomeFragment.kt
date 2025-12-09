package com.example.dashboard.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.dashboard.R
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

        binding.cardDrive.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_dashboard)
        }

        binding.cardMaintenance.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_maintenance)
        }

        binding.btnProfile.setOnClickListener {
            val dialog = ProfileDialogFragment()
            dialog.show(parentFragmentManager, "ProfileDialog")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}