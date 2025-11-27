package com.example.dashboard.ui // <-- Ton package

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.dashboard.R
import com.example.dashboard.databinding.FragmentDashboardBinding
import com.example.dashboard.service.ObdManager
import com.example.dashboard.utils.PolylineDecoder
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class DashboardFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private var map: GoogleMap? = null

    // GPS & Location
    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient
    private var locationCallback: com.google.android.gms.location.LocationCallback? = null

    // --- CORRECTION 1 : Variable d'état pour le suivi ---
    private var isTrackingMode = true

    // HTTP pour la route
    private val client = OkHttpClient()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(requireActivity())

        val mapFragment = childFragmentManager.findFragmentById(R.id.map_fragment) as SupportMapFragment?
        mapFragment?.getMapAsync(this)

        // Flows OBD (Vitesse / RPM)
        lifecycleScope.launch { ObdManager.currentSpeed.collect { binding.tvSpeed.text = it.toString() } }
        lifecycleScope.launch { ObdManager.currentRpm.collect { binding.tvRpm.text = it.toString() } }

        // Bouton Maison
        binding.btnHomeShortcut.setOnClickListener {
            // Remplace par tes coordonnées cibles
            startNavigationTo("47.26196793105004, 5.966234481447267") // Exemple Dijon
        }

        // Bouton Maison
        binding.btnTaffeShortcut.setOnClickListener {
            // Remplace par tes coordonnées cibles
            startNavigationTo("47.18426845610741, 5.814506625618502") // Exemple Kelly
        }

        // Lancer la connexion OBD
        startObdConnection()
    }

    @SuppressLint("MissingPermission")
    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map?.isMyLocationEnabled = true
        map?.uiSettings?.isMyLocationButtonEnabled = true
        map?.uiSettings?.isCompassEnabled = true

        // --- CORRECTION 2 : Gestion intelligente du suivi ---

        // Si l'utilisateur touche la carte (pan/zoom), on désactive le suivi automatique
        map?.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                isTrackingMode = false
                // On peut afficher un petit Toast discret ou rien du tout
            }
        }

        // Si l'utilisateur clique sur le bouton "Cible" (re-centrer), on réactive le suivi
        map?.setOnMyLocationButtonClickListener {
            isTrackingMode = true
            Toast.makeText(context, "Mode Suivi activé", Toast.LENGTH_SHORT).show()
            false // Retourne false pour laisser Google Maps faire l'animation de centrage par défaut
        }

        startLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 1000
        ).build()

        locationCallback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(locationResult: com.google.android.gms.location.LocationResult) {
                for (location in locationResult.locations) {
                    // --- CORRECTION 3 : On ne bouge la caméra que si le mode Suivi est actif ---
                    if (map != null && isTrackingMode) {
                        val currentLatLng = LatLng(location.latitude, location.longitude)

                        val cameraUpdate = CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(currentLatLng)
                                .zoom(18f)        // Zoom assez proche
                                .bearing(location.bearing) // Rotation selon le cap de la voiture
                                .tilt(0f)        // Effet 3D prononcé
                                .build()
                        )
                        map?.animateCamera(cameraUpdate)
                    }
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, android.os.Looper.getMainLooper())
    }

    private fun startNavigationTo(destination: String) {
        // ... (Ton code précédent pour vérifier la location) ...
        val currentLoc = try { map?.myLocation } catch (e: SecurityException) { null } ?: return
        val origin = "${currentLoc.latitude},${currentLoc.longitude}"
        val apiKey = getString(R.string.google_maps_key) // Ou ta clé en dur

        val url = "https://maps.googleapis.com/maps/api/directions/json?origin=$origin&destination=$destination&mode=driving&key=$apiKey"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val jsonData = response.body?.string()

                if (jsonData != null) {
                    val jsonObject = JSONObject(jsonData)
                    val routes = jsonObject.getJSONArray("routes")

                    if (routes.length() > 0) {
                        val points = routes.getJSONObject(0)
                            .getJSONObject("overview_polyline")
                            .getString("points")

                        val decodedPath = PolylineDecoder.decode(points)

                        withContext(Dispatchers.Main) {
                            drawRouteOnMap(decodedPath)
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun drawRouteOnMap(path: List<LatLng>) {
        map?.clear() // Attention, cela efface aussi les autres markers s'il y en a

        // --- CORRECTION 4 : Tracé de la route ---
        val polylineOptions = PolylineOptions()
            .addAll(path)
            .width(20f)       // Un peu plus large pour être bien visible
            .color(Color.BLUE)
            .geodesic(false)  // <--- IMPORTANT : Mettre false pour suivre la route et pas faire un trait "avion"
            .jointType(com.google.android.gms.maps.model.JointType.ROUND) // Jolis coins arrondis

        map?.addPolyline(polylineOptions)

        // Réactiver le suivi pour remettre la caméra sur la voiture au départ
        isTrackingMode = true
    }

    private fun startObdConnection() {
        // 1. DÉSACTIVER LA SIMULATION
        ObdManager.isMockMode = true

        // 2. TON ADRESSE MAC (ELM 327)
        val macAddressELM = "use/your/value"

        lifecycleScope.launch {
            // On récupère l'adaptateur Bluetooth du téléphone
            val bluetoothManager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter

            // Vérification basique que le Bluetooth est allumé
            if (adapter == null || !adapter.isEnabled) {
                Toast.makeText(context, "Bluetooth éteint !", Toast.LENGTH_SHORT).show()
                return@launch
            }

            binding.tvSpeed.text = "..." // Feedback visuel de tentative

            // Tentative de connexion
            val success = ObdManager.connect(macAddressELM, adapter)

            if (success) {
                Toast.makeText(context, "Connecté à la 206+ !", Toast.LENGTH_SHORT).show()
                // Lancement de la boucle de lecture
                ObdManager.startDataLoop()
            } else {
                Toast.makeText(context, "Échec connexion OBD (Vérifie le contact)", Toast.LENGTH_LONG).show()
                // Si ça rate, on remet le mock pour pas que l'appli soit vide
                ObdManager.isMockMode = true
                ObdManager.startDataLoop()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        _binding = null
    }
}