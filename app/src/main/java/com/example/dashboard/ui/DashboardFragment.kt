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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashboardFragment : Fragment(), OnMapReadyCallback {
    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private var map: GoogleMap? = null

    // Pour la logique de rafraîchissement intelligent
    private var lastApiCallTime: Long = 0
    private var lastApiCallLocation: android.location.Location? = null
    private var currentSpeedKmH: Float = 0f // On la mettra à jour via le GPS ou l'OBD

    private var currentFavoritesList: List<com.example.dashboard.data.SavedAddress> = emptyList()
    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient
    private var locationCallback: com.google.android.gms.location.LocationCallback? = null
    private var isTrackingMode = true
    private val profileViewModel: ProfileViewModel by viewModels()
    private var lastLocation: Location? = null
    private val savedAddressViewModel: SavedAddressViewModel by viewModels()
    private var currentDestination: LatLng? = null
    private var currentAddressName: String = ""

    private var lastRecalculationLocation: Location? = null

    // Pour gérer la boucle de rafraichissement
    private val navigationHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isNavigationActive = false
    private val REFRESH_INTERVAL = 10000L // 45 secondes (Bon compromis)

    private val navigationRunnable = object : Runnable {
        override fun run() {
            if (isNavigationActive && currentDestinationCoords != null) {
                // On récupère la position actuelle
                val myLoc = try { map?.myLocation } catch (e: Exception) { null }

                if (myLoc != null) {
                    val currentTime = System.currentTimeMillis()

                    // Si c'est le tout premier appel
                    if (lastApiCallLocation == null) {
                        performRefresh(myLoc)
                    } else {
                        // Calculs des deltas
                        val distanceTraveled = myLoc.distanceTo(lastApiCallLocation!!) // en mètres
                        val timeElapsed = currentTime - lastApiCallTime // en millisecondes

                        // DÉFINITION DES SEUILS SELON LA VITESSE
                        val (distThreshold, timeThreshold) = when {
                            currentSpeedKmH < 50 -> Pair(200, 60_000L)      // Ville : 200m ou 1min
                            currentSpeedKmH < 90 -> Pair(1000, 90_000L)     // Route : 1km ou 1min30
                            else -> Pair(3000, 180_000L)                    // Autoroute : 3km ou 3min
                        }

                        // VERIFICATION
                        // On rafraîchit SI (Distance dépassée OU Temps dépassé)
                        // ET qu'on a bougé un minimum (pour éviter boucle infinie à l'arrêt complet strict)
                        if (distanceTraveled > distThreshold || timeElapsed > timeThreshold) {
                            if (distanceTraveled > 20) { // Anti-surplace
                                android.util.Log.d("GPS_SMART", "Refresh: Vitesse=$currentSpeedKmH km/h, Dist=$distanceTraveled m")
                                performRefresh(myLoc)
                            }
                        }
                    }
                }

                // On revérifie les conditions dans 5 secondes
                // (Ce n'est pas un appel API, juste un check de condition "if", donc très léger)
                navigationHandler.postDelayed(this, 5000L)
            }
        }
    }

    // Petite fonction helper pour éviter de dupliquer le code
    private fun performRefresh(location: android.location.Location) {
        startNavigationTo(currentDestinationCoords!!)

        // On mémorise l'état de cet appel
        lastApiCallTime = System.currentTimeMillis()
        // On crée une copie de la location pour ne pas garder une référence qui bouge
        val locSnapshot = android.location.Location("memory")
        locSnapshot.latitude = location.latitude
        locSnapshot.longitude = location.longitude
        lastApiCallLocation = locSnapshot
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
//        binding.btnHome.setOnClickListener { handleShortcut("Domicile") }
//        binding.btnWork.setOnClickListener { handleShortcut("Travail") }

        binding.btnSaveCurrent.setOnClickListener {
            // map?.myLocation est l'accès le plus fiable à la dernière position connue par la map
            val lastLocation = map?.myLocation
            if (lastLocation == null) {
                Toast.makeText(context, "Position GPS introuvable. Activez le suivi.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showSaveLocationDialog(lastLocation.latitude, lastLocation.longitude)
        }

        // 2. GESTION DE LA SUPPRESSION (Bouton Poubelle)
        // ... (code précédent de création de l'adapter) ...

        binding.btnSearch.setOnClickListener {
            if (binding.searchContainer.visibility == View.GONE) {
                binding.searchContainer.alpha = 0f
                binding.searchContainer.visibility = View.VISIBLE
                binding.searchContainer.animate().alpha(1f).setDuration(300).start()
            } else {
                binding.searchContainer.animate().alpha(0f).setDuration(300).withEndAction {
                    binding.searchContainer.visibility = View.GONE
                }.start()
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

//        binding.btnSaveFavorite.setOnClickListener {
//            if (currentDestination != null && binding.btnSaveFavorite.tag != "saved") {
//                showSaveFavoriteDialog()
//            } else {
//                showSaveFavoriteDialog()
//            }
//        }

        lifecycleScope.launch {
            savedAddressViewModel.savedAddresses.collect { savedList ->
                currentFavoritesList = savedList
                // On met à jour le texte du bouton pour faire joli (optionnel)
                //binding.btnOpenFavorites.text = "📂  Mes Favoris (${savedList.size})"
            }
        }

        binding.btnOpenFavorites.setOnClickListener {
            showFavoritesListDialog()
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

    private fun showFavoritesListDialog() {
        if (currentFavoritesList.isEmpty()) {
            Toast.makeText(context, "Aucun favori enregistré.", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. On prépare les noms à afficher
        val names = currentFavoritesList.map { it.name }.toTypedArray()

        // 2. On crée une ListView manuellement pour gérer les Clics Longs
        val listView = android.widget.ListView(requireContext())
        val adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, names)
        listView.adapter = adapter

        // 3. Création de la fenêtre
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Mes Destinations")
            .setView(listView)
            .setNegativeButton("Fermer", null)
            .create()

        // 4. GESTION DU CLIC COURT (Navigation)
        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedFav = currentFavoritesList[position]

            // Action : Navigation
            binding.etSearch.setText(selectedFav.name)
            val destCoords = "${selectedFav.latitude},${selectedFav.longitude}"
            startNavigationTo(destCoords)

            dialog.dismiss() // On ferme la liste
        }

        // 5. GESTION DU CLIC LONG (Menu Modifier / Supprimer)
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val selectedFav = currentFavoritesList[position]

            // On ouvre une petite fenêtre de choix
            val options = arrayOf("Modifier le nom", "Supprimer")
            AlertDialog.Builder(requireContext())
                .setTitle("Gérer '${selectedFav.name}'")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> showEditFavoriteDialog(selectedFav) // Modifier
                        1 -> deleteFavoriteConfirm(selectedFav)  // Supprimer
                    }
                }
                .show()

            true // Indique qu'on a géré l'événement (pour ne pas déclencher le clic court en même temps)
        }

        dialog.show()
    }

    // Petite fonction pour confirmer la suppression
    private fun deleteFavoriteConfirm(item: com.example.dashboard.data.SavedAddress) {
        AlertDialog.Builder(requireContext())
            .setTitle("Supprimer ${item.name} ?")
            .setPositiveButton("Oui") { _, _ ->
                savedAddressViewModel.deleteFavorite(item)
                Toast.makeText(context, "Supprimé !", Toast.LENGTH_SHORT).show()
                // Pas besoin de rafraîchir manuellement, le collect le fera et fermera/rouvrira la liste si besoin
            }
            .setNegativeButton("Non", null)
            .show()
    }

    // Petite fonction pour modifier le nom
    private fun showEditFavoriteDialog(item: com.example.dashboard.data.SavedAddress) {
        val input = EditText(requireContext())
        input.setText(item.name)

        AlertDialog.Builder(requireContext())
            .setTitle("Renommer")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString()
                savedAddressViewModel.updateFavoriteName(item, newName)
                Toast.makeText(context, "Modifié !", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showSaveLocationDialog(lat: Double, lon: Double) {
        val input = EditText(requireContext())
        // Nom par défaut
        input.setText("Position Sauvée ${java.text.SimpleDateFormat("HH:mm").format(java.util.Date())}")

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Nommer le Favori")
            .setView(input)
            .setPositiveButton("Enregistrer") { _, _ ->
                val name = input.text.toString().trim()
                val address = input.text.toString()
                if (name.isNotBlank()) {
                    // Appel au ViewModel pour la sauvegarde
                    savedAddressViewModel.addFavorite(name, address, lat, lon)
                    Toast.makeText(context, "$name ajouté aux favoris.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
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
//                    binding.btnSaveFavorite.setImageResource(android.R.drawable.btn_star_big_on)
//                    binding.btnSaveFavorite.tag = "saved"
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
//                        binding.btnSaveFavorite.setImageResource(android.R.drawable.btn_star_big_off)
//                        binding.btnSaveFavorite.tag = "unsaved"
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
                    if (location.hasSpeed()) {
                        currentSpeedKmH = location.speed * 3.6f // Conversion m/s en km/h
                    }
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

            val now = System.currentTimeMillis()
            val arrivalTimeMillis = now + (timeSeconds * 1000L)

            val dateFormat = SimpleDateFormat("HH'h'mm", Locale.getDefault())
            val arrivalFormatted = dateFormat.format(Date(arrivalTimeMillis.toLong()))

            // SimpleDateFormat("HH'h'mm", Locale.getDefault()).format(Date((System.currentTimeMillis() + (timeSeconds * 1000L)).toLong()))

            binding.tvTripEnd.text = arrivalFormatted

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
                        val durationSeconds = leg.getJSONObject("duration").getLong("value")
                        val endText = SimpleDateFormat("HH'h'mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + (durationSeconds * 1000L)))

                        withContext(Dispatchers.Main) {
                            drawRouteOnMap(decodedPath)
                            binding.cardTripInfo.visibility = View.VISIBLE
                            binding.tvTripDistance.text = distanceText
                            binding.tvTripTime.text = durationText
                            binding.tvTripEnd.text = endText
                        }
                        binding.searchContainer.visibility = View.GONE
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
