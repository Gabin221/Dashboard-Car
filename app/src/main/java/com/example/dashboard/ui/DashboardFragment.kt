package com.example.dashboard.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.location.Location
import androidx.fragment.app.viewModels
import com.example.dashboard.ui.ProfileViewModel
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
import android.location.Geocoder
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import java.util.Locale

class DashboardFragment : Fragment(), OnMapReadyCallback {
    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private var map: GoogleMap? = null

    private var currentFavoritesList: List<com.example.dashboard.data.SavedAddress> = emptyList()
    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient
    private var locationCallback: com.google.android.gms.location.LocationCallback? = null
    private var isTrackingMode = true
    private val profileViewModel: ProfileViewModel by viewModels()
    private var lastLocation: Location? = null
    private val savedAddressViewModel: SavedAddressViewModel by viewModels()
    private var currentDestination: LatLng? = null
    private var currentAddressName: String = ""

    private var lastRecalculationLocation: android.location.Location? = null

    // Pour gérer la boucle de rafraichissement
    private val navigationHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isNavigationActive = false
    private val REFRESH_INTERVAL = 45000L // 45 secondes (Bon compromis)

    private val navigationRunnable = object : Runnable {
        override fun run() {
            if (isNavigationActive && currentDestinationCoords != null) {
                // On récupère la position actuelle via la map (ou via ta variable lastLocation)
                val myLoc = try { map?.myLocation } catch (e: Exception) { null }

                // LOGIQUE INTELLIGENTE
                var shouldRefresh = false

                if (myLoc != null) {
                    if (lastRecalculationLocation == null) {
                        shouldRefresh = true // Premier lancement
                    } else {
                        val distance = myLoc.distanceTo(lastRecalculationLocation!!) // en mètres
                        // Si on a bougé de plus de 100m (on avance)
                        // OU si on a bougé de plus de 30m ET qu'on est à une intersection (difficile à savoir, donc on fait au temps court)
                        if (distance > 100) {
                            shouldRefresh = true
                        }
                    }
                }

                if (shouldRefresh) {
                    startNavigationTo(currentDestinationCoords!!)
                    // On convertit LatLng en Location pour la mémoire
                    val loc = android.location.Location("memory")
                    loc.latitude = myLoc?.latitude ?: 0.0
                    loc.longitude = myLoc?.longitude ?: 0.0
                    lastRecalculationLocation = loc

                    // Prochain check dans 30 secondes (plus fréquent pour vérifier le mouvement)
                    navigationHandler.postDelayed(this, 30000L)
                } else {
                    // On n'a pas bougé (bouchon), on ne spamme pas l'API.
                    // On revérifie dans 10 secondes si ça s'est débloqué
                    android.util.Log.d("GPS", "Bouchon détecté ou arrêt : pas de recalcul.")
                    navigationHandler.postDelayed(this, 10000L)
                }
            }
        }
    }

    // Pour mémoriser où on va
    private var currentDestinationCoords: String? = null
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
        lifecycleScope.launch { ObdManager.currentData1.collect { binding.tvData1.text = it.toString() } }
        lifecycleScope.launch { ObdManager.currentData2.collect { binding.tvData2.text = it.toString() } }

        // 1. GESTION DES BOUTONS MAISON / TRAVAIL
        binding.btnHome.setOnClickListener { handleShortcut("Domicile") }
        binding.btnWork.setOnClickListener { handleShortcut("Travail") }

        // 2. GESTION DE LA SUPPRESSION (Bouton Poubelle)
        binding.btnManageFavorites.setOnClickListener {
            val position = binding.spinnerFavorites.selectedItemPosition
            if (position > 0) {
                // position - 1 car l'index 0 est le titre "Favoris..."
                // On récupère l'objet directement depuis notre liste locale, c'est FIABLE.
                val itemToDelete = currentFavoritesList[position - 1]

                AlertDialog.Builder(requireContext())
                    .setTitle("Supprimer ${itemToDelete.name} ?")
                    .setPositiveButton("Oui") { _, _ ->
                        savedAddressViewModel.deleteFavorite(itemToDelete)
                        Toast.makeText(context, "Supprimé !", Toast.LENGTH_SHORT).show()
                        binding.spinnerFavorites.setSelection(0)
                    }
                    .setNegativeButton("Non", null)
                    .show()
            }
        }

        binding.btnUpdateFavorites.setOnClickListener {
            val position = binding.spinnerFavorites.selectedItemPosition
            if (position > 0) {
                val itemToEdit = currentFavoritesList[position - 1]

                // Ouvre une modale avec un champ texte pré-rempli
                val input = EditText(requireContext())
                input.setText(itemToEdit.name)

                AlertDialog.Builder(requireContext())
                    .setTitle("Renommer")
                    .setView(input)
                    .setPositiveButton("OK") { _, _ ->
                        val newName = input.text.toString()
                        // Tu dois ajouter une fonction updateFavorite dans ton ViewModel
                        // savedAddressViewModel.updateFavoriteName(itemToEdit, newName)
                    }
                    .show()
            }
        }

        binding.btnSearchGo.setOnClickListener {
            val address = binding.etSearch.text.toString()
            if (address.isNotEmpty()) {
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(view.windowToken, 0)
                searchAndNavigate(address)
            }
        }

        binding.btnSaveFavorite.setOnClickListener {
            if (currentDestination != null && binding.btnSaveFavorite.tag != "saved") {
                showSaveFavoriteDialog()
            } else {
                showSaveFavoriteDialog()
            }
        }

        lifecycleScope.launch {
            savedAddressViewModel.savedAddresses.collect { savedList ->
                currentFavoritesList = savedList
                val spinnerItems = mutableListOf("fav...")
                spinnerItems.addAll(savedList.map { it.name })
                val adapter = android.widget.ArrayAdapter(requireContext(), R.layout.spinner_item, spinnerItems)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerFavorites.adapter = adapter
                binding.spinnerFavorites.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                        if (position > 0) {
                            val selectedFav = savedList[position - 1]
                            binding.etSearch.setText(selectedFav.name)
                            val dest = "${selectedFav.latitude},${selectedFav.longitude}"
                            startNavigationTo(dest)
                            binding.spinnerFavorites.setSelection(0)
                        }
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
                }
            }
        }

        val fakeFavorites = listOf("Favoris...", "Maison", "Boulot")
        val adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, fakeFavorites)
        binding.spinnerFavorites.adapter = adapter
        binding.spinnerFavorites.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    val selected = fakeFavorites[position]
                    binding.etSearch.setText(selected)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        // Clic sur la croix -> Efface le trajet
        binding.btnClearRoute.setOnClickListener {
            map?.clear()
            binding.cardTripInfo.visibility = View.GONE
            isNavigationActive = false // Coupe le circuit
            navigationHandler.removeCallbacks(navigationRunnable) // Arrête le chrono
            currentDestinationCoords = null

            // Remet le suivi standard
            isTrackingMode = true
        }

        // Lancer la connexion OBD
        startObdConnection()
    }

    private fun handleShortcut(name: String) {
        lifecycleScope.launch {
            val shortcut = savedAddressViewModel.getAddressByName(name)
            if (shortcut != null) {
                startNavigationTo("${shortcut.latitude},${shortcut.longitude}")
                Toast.makeText(context, "Direction $name !", Toast.LENGTH_SHORT).show()
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "$name n'est pas défini", Toast.LENGTH_SHORT).show()
                    showCreateShortcutDialog(name)
                }
            }
        }
    }

    private fun showCreateShortcutDialog(name: String) {
        val input = EditText(requireContext())
        input.hint = "Adresse pour $name (ex: 10 rue...)"
        AlertDialog.Builder(requireContext())
            .setTitle("Définir $name")
            .setView(input)
            .setPositiveButton("Rechercher & Sauvegarder") { _, _ ->
                val address = input.text.toString()
                if (address.isNotEmpty()) {
                    searchAndSaveShortcut(address, name)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun searchAndSaveShortcut(query: String, shortcutName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val geocoder = Geocoder(requireContext(), Locale.getDefault())
            try {
                val results = geocoder.getFromLocationName(query, 1)
                if (results != null && results.isNotEmpty()) {
                    val loc = results[0]
                    savedAddressViewModel.addFavorite(shortcutName, loc.getAddressLine(0) ?: query, loc.latitude, loc.longitude)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "$shortcutName enregistré !", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Adresse introuvable", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun showSaveFavoriteDialog() {
        val context = requireContext()
        val inputName = EditText(context).apply {
            hint = "Nom du favori (ex: Aldi Coin de rue)"
            setText(currentAddressName)
        }
        val container = android.widget.FrameLayout(context)
        val params = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = 50; rightMargin = 50 }
        inputName.layoutParams = params
        container.addView(inputName)
        AlertDialog.Builder(context)
            .setTitle("Ajouter aux favoris")
            .setView(container)
            .setPositiveButton("Sauvegarder") { _, _ ->
                val name = inputName.text.toString()
                if (name.isNotEmpty() && currentDestination != null) {
                    savedAddressViewModel.addFavorite(
                        name,
                        currentAddressName,
                        currentDestination!!.latitude,
                        currentDestination!!.longitude
                    )
                    binding.btnSaveFavorite.setImageResource(android.R.drawable.btn_star_big_on)
                    binding.btnSaveFavorite.tag = "saved"
                    Toast.makeText(context, "Enregistré !", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showSearchDialog() {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        val inputAddress = EditText(context).apply { hint = "Adresse ou Ville (ex: Paris)" }
        layout.addView(inputAddress)
        AlertDialog.Builder(context)
            .setTitle("Où va-t-on ?")
            .setView(layout)
            .setPositiveButton("Y aller") { _, _ ->
                val address = inputAddress.text.toString()
                if (address.isNotEmpty()) {
                    searchAndNavigate(address)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun searchAndNavigate(query: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(requireContext(), Locale.getDefault())
                val myPos = try { map?.myLocation } catch (e: Exception) { null }
                var results: MutableList<android.location.Address>? = null
                if (myPos != null) {
                    val lat = myPos.latitude
                    val lng = myPos.longitude
                    try {
                        results = geocoder.getFromLocationName(query, 1, lat - 0.4, lng - 0.4, lat + 0.4, lng + 0.4)
                    } catch (e: Exception) {}
                    if (results.isNullOrEmpty()) {
                        try {
                            val myAddressInfo = geocoder.getFromLocation(lat, lng, 1)
                            if (myAddressInfo != null && myAddressInfo.isNotEmpty()) {
                                val myCity = myAddressInfo[0].locality
                                val newQuery = "$query $myCity"
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Tentative avec : $newQuery", Toast.LENGTH_SHORT).show()
                                }
                                results = geocoder.getFromLocationName(newQuery, 1)
                            }
                        } catch (e: Exception) {}
                    }
                }
                if (results.isNullOrEmpty()) {
                    results = geocoder.getFromLocationName(query, 1)
                }
                if (results != null && results.isNotEmpty()) {
                    val location = results[0]
                    val destinationCoords = "${location.latitude},${location.longitude}"
                    currentDestination = LatLng(location.latitude, location.longitude)
                    currentAddressName = location.featureName ?: query
                    val fullAddress = location.getAddressLine(0) ?: query
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Go : $fullAddress", Toast.LENGTH_SHORT).show()
                        startNavigationTo(destinationCoords)
                        binding.btnSaveFavorite.setImageResource(android.R.drawable.btn_star_big_off)
                        binding.btnSaveFavorite.tag = "unsaved"
                        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.hideSoftInputFromWindow(view?.windowToken, 0)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Aucun résultat pour '$query' 😕", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Erreur réseau/geocoder", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map?.isMyLocationEnabled = true
        map?.uiSettings?.isMyLocationButtonEnabled = true
        map?.uiSettings?.isCompassEnabled = true
        map?.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                isTrackingMode = false
            }
        }
        map?.setOnMyLocationButtonClickListener {
            isTrackingMode = true
            Toast.makeText(context, "Mode Suivi activé", Toast.LENGTH_SHORT).show()
            false
        }

        map?.setPadding(0, 500, 0, 0)

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
                    if (map != null && isTrackingMode) {
                        val currentLatLng = LatLng(location.latitude, location.longitude)
                        val bearing = if (location.hasBearing() && location.speed > 2) {
                            location.bearing
                        } else {
                            map!!.cameraPosition.bearing // On garde la dernière connue
                        }
                        val cameraUpdate = CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(currentLatLng)
                                .zoom(16.5f)
                                .bearing(bearing)
                                .tilt(0f)
                                .build()
                        )
                        map?.animateCamera(cameraUpdate)
                        updateTripInfo(location)
                    }
                    if (lastLocation != null) {
                        val distanceInMeters = location.distanceTo(lastLocation!!)
                        if (distanceInMeters > 2) {
                            val distanceInKm = distanceInMeters / 1000.0
                            profileViewModel.addDistanceToProfile(distanceInKm, requireContext())
                        }
                    }
                    lastLocation = location
                }
            }
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, android.os.Looper.getMainLooper())
    }

    private fun updateTripInfo(currentLocation: Location) {
        if (currentDestination != null && binding.cardTripInfo.visibility == View.VISIBLE) {
            val destLoc = Location("dest")
            destLoc.latitude = currentDestination!!.latitude
            destLoc.longitude = currentDestination!!.longitude

            // Distance restante en mètres
            val distanceMeters = currentLocation.distanceTo(destLoc)

            // Affichage Distance
            val distKm = distanceMeters / 1000.0
            binding.tvTripDistance.text = String.format("%.1f km", distKm)

            // Estimation Temps (Basique : Moyenne 50km/h en ville/mixte)
            // Temps = Distance / Vitesse.
            // Vitesse actuelle (m/s). Si arrêt (0), on suppose 50km/h (13.8 m/s)
            val speed = if (currentLocation.hasSpeed() && currentLocation.speed > 2) currentLocation.speed else 13.8f
            val timeSeconds = distanceMeters / speed
            val minutes = (timeSeconds / 60).toInt()

            // Formatage propre (Heures / Minutes)
            if (minutes > 60) {
                val h = minutes / 60
                val m = minutes % 60
                binding.tvTripTime.text = "${h}h ${m}min"
            } else {
                binding.tvTripTime.text = "$minutes min"
            }

            // Détection arrivée (si moins de 50m)
            if (distanceMeters < 50) {
                binding.tvTripTime.text = "Arrivé !"
                binding.tvTripTime.setTextColor(Color.GREEN)
                // Tu pourrais ici arrêter la navigation automatiquement
            }
        }
    }

    private fun startNavigationTo(destination: String) {
        currentDestinationCoords = destination

        // Si c'est la PREMIÈRE fois qu'on lance (pas un refresh auto), on active la boucle
        if (!isNavigationActive) {
            isNavigationActive = true
            navigationHandler.post(navigationRunnable) // Lance la boucle
            binding.btnClearRoute.visibility = View.VISIBLE
            binding.cardTripInfo.visibility = View.VISIBLE
        }
        val currentLoc = try { map?.myLocation } catch (e: SecurityException) { null } ?: return
        val origin = "${currentLoc.latitude},${currentLoc.longitude}"
        val apiKey = getString(R.string.google_maps_key)
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
                        val route = routes.getJSONObject(0)
                        val points = route.getJSONObject("overview_polyline").getString("points")
                        val decodedPath = PolylineDecoder.decode(points)
                        val legs = route.getJSONArray("legs")
                        val leg = legs.getJSONObject(0)
                        val distanceText = leg.getJSONObject("distance").getString("text")
                        val durationText = leg.getJSONObject("duration").getString("text")
                        withContext(Dispatchers.Main) {
                            drawRouteOnMap(decodedPath)
                            binding.cardTripInfo.visibility = View.VISIBLE
                            binding.tvTripDistance.text = distanceText
                            binding.tvTripTime.text = durationText
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun drawRouteOnMap(path: List<LatLng>) {
        map?.clear()
        val polylineOptions = PolylineOptions()
            .addAll(path)
            .width(20f)
            .color(Color.BLUE)
            .geodesic(false)
            .jointType(com.google.android.gms.maps.model.JointType.ROUND)
        map?.addPolyline(polylineOptions)
        isTrackingMode = true
    }

    private fun startObdConnection() {
        ObdManager.isMockMode = true
        val macAddressELM = "use/your/value"
        lifecycleScope.launch {
            val bluetoothManager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
            if (adapter == null || !adapter.isEnabled) {
                Toast.makeText(context, "Bluetooth éteint !", Toast.LENGTH_SHORT).show()
                return@launch
            }
            binding.tvSpeed.text = "..."
            val success = ObdManager.connect(macAddressELM, adapter)
            if (success) {
                Toast.makeText(context, "Connecté à la 206+ !", Toast.LENGTH_SHORT).show()
                ObdManager.startDataLoop()
            } else {
                Toast.makeText(context, "Échec connexion OBD (Vérifie le contact)", Toast.LENGTH_LONG).show()
                ObdManager.isMockMode = true
                ObdManager.startDataLoop()
            }
        }
    }

    override fun onDestroyView() {
        navigationHandler.removeCallbacks(navigationRunnable)
        super.onDestroyView()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        _binding = null
    }
}
