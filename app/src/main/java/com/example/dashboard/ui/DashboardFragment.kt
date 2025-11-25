package com.example.dashboard.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.dashboard.R // Vérifie l'import
import com.example.dashboard.databinding.FragmentDashboardBinding
import com.example.dashboard.service.ObdManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.launch

// Ajoute l'interface OnMapReadyCallback ici
class DashboardFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private var map: GoogleMap? = null // Variable pour stocker la carte

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Initialiser la Map
        val mapFragment = childFragmentManager.findFragmentById(R.id.map_fragment) as SupportMapFragment?
        mapFragment?.getMapAsync(this) // Cela va appeler onMapReady quand c'est chargé

        // ... Ton code existant pour observer RPM/Speed ...
        lifecycleScope.launch {
            ObdManager.currentSpeed.collect { binding.tvSpeed.text = it.toString() }
        }
        lifecycleScope.launch {
            ObdManager.currentRpm.collect { binding.tvRpm.text = it.toString() }
        }

        startObdConnection()
    }

    // Cette fonction est appelée quand Google Maps est prêt
    @SuppressLint("MissingPermission") // On a déjà demandé la permission dans MainActivity
    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap

        // Activer le point bleu (Ma position)
        map?.isMyLocationEnabled = true

        // Activer le bouton pour se recentrer
        map?.uiSettings?.isMyLocationButtonEnabled = true

        // Optionnel : Zoomer sur la France par défaut ou ta dernière position connue
        // (Pour l'instant on laisse Google Maps centrer par défaut)
    }

    private fun startObdConnection() {
        // ... Ton code existant ...
    }

    // ... onDestroyView ...
}