package com.example.dashboard.ui // <-- Ton package

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.location.Location // Ajoute cet import
import androidx.fragment.app.viewModels // Ajoute cet import
import com.example.dashboard.ui.ProfileViewModel // Ajoute cet import
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

    // GPS & Location
    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient
    private var locationCallback: com.google.android.gms.location.LocationCallback? = null

    // --- CORRECTION 1 : Variable d'état pour le suivi ---
    private var isTrackingMode = true

    // AJOUT 1 : Le ViewModel pour sauvegarder les KM
    private val profileViewModel: ProfileViewModel by viewModels()

    // AJOUT 2 : Pour mémoriser où on était il y a 1 seconde
    private var lastLocation: Location? = null

    private val savedAddressViewModel: SavedAddressViewModel by viewModels()
    private var currentDestination: com.google.android.gms.maps.model.LatLng? = null
    private var currentAddressName: String = ""

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

        // 1. GESTION DES BOUTONS MAISON / TRAVAIL
        binding.btnHome.setOnClickListener { handleShortcut("Domicile") }
        binding.btnWork.setOnClickListener { handleShortcut("Travail") }

        // 2. GESTION DE LA SUPPRESSION (Bouton Poubelle)
        binding.btnManageFavorites.setOnClickListener {
            // On récupère l'objet sélectionné dans le Spinner
            val position = binding.spinnerFavorites.selectedItemPosition
            if (position > 0) { // Si ce n'est pas le header "Favoris..."
                // ASTUCE : On doit récupérer la vraie liste des favoris stockée dans le ViewModel
                // Pour faire simple ici, on va demander confirmation
                val selectedName = binding.spinnerFavorites.selectedItem.toString()

                AlertDialog.Builder(requireContext())
                    .setTitle("Supprimer $selectedName ?")
                    .setMessage("Ce favori sera effacé définitivement.")
                    .setPositiveButton("Supprimer") { _, _ ->
                        // On lance une coroutine pour trouver et supprimer
                        lifecycleScope.launch {
                            val itemToDelete = savedAddressViewModel.getAddressByName(selectedName)
                            if (itemToDelete != null) {
                                savedAddressViewModel.deleteFavorite(itemToDelete)
                                Toast.makeText(context, "Supprimé !", Toast.LENGTH_SHORT).show()
                                binding.spinnerFavorites.setSelection(0) // Reset
                            }
                        }
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            } else {
                Toast.makeText(context, "Sélectionnez d'abord un favori dans la liste", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSearchGo.setOnClickListener {
            val address = binding.etSearch.text.toString()
            if (address.isNotEmpty()) {
                // On cache le clavier pour le confort
                val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(view.windowToken, 0)

                // On lance la recherche
                searchAndNavigate(address)
            }
        }

        binding.btnSaveFavorite.setOnClickListener {
            if (currentDestination != null && binding.btnSaveFavorite.tag != "saved") {
                showSaveFavoriteDialog()
            } else {
                showSaveFavoriteDialog()
                // Toast.makeText(context, "Déjà enregistré ou pas de destination", Toast.LENGTH_SHORT).show()
            }
        }

        lifecycleScope.launch {
            savedAddressViewModel.savedAddresses.collect { savedList ->
                // On prépare la liste pour le Spinner
                // On crée une liste d'objets simple pour l'affichage (String)
                val spinnerItems = mutableListOf("❤  Mes Favoris...") // Header

                // On ajoute les favoris de la BDD
                spinnerItems.addAll(savedList.map { it.name })

                val adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, spinnerItems)
                binding.spinnerFavorites.adapter = adapter

                // Gestion du clic sur un favori
                binding.spinnerFavorites.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                        if (position > 0) { // On ignore le header
                            // On retrouve l'objet complet grâce à l'index (position - 1 car il y a le header)
                            val selectedFav = savedList[position - 1]

                            binding.etSearch.setText(selectedFav.name)
                            // On lance la nav direct !
                            val dest = "${selectedFav.latitude},${selectedFav.longitude}"
                            startNavigationTo(dest)

                            // On remet le spinner à 0 pour pouvoir resélectionner le même plus tard si besoin
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

        // Gestion du clic sur le Spinner
        binding.spinnerFavorites.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                if (position > 0) { // On ignore le premier élément "Favoris..."
                    val selected = fakeFavorites[position]
                    binding.etSearch.setText(selected) // Ça remplit la barre
                    // Tu pourras lancer la navigation directement ici plus tard
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        // Clic sur la croix -> Efface le trajet
        binding.btnClearRoute.setOnClickListener {
            map?.clear() // Efface la ligne bleue
            binding.cardTripInfo.visibility = View.GONE // Cache la carte info
            isTrackingMode = true // Réactive le suivi auto
        }

        // Lancer la connexion OBD
        startObdConnection()
    }

    private fun handleShortcut(name: String) {
        lifecycleScope.launch {
            val shortcut = savedAddressViewModel.getAddressByName(name)
            if (shortcut != null) {
                // Si l'adresse existe, on y va
                startNavigationTo("${shortcut.latitude},${shortcut.longitude}")
                Toast.makeText(context, "Direction $name !", Toast.LENGTH_SHORT).show()
            } else {
                // Sinon, on propose de la créer
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
                    // On lance une recherche spéciale qui sauvegardera automatiquement
                    searchAndSaveShortcut(address, name)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun searchAndSaveShortcut(query: String, shortcutName: String) {
        // Tu peux réutiliser la logique de searchAndNavigate mais en sauvegardant directement à la fin
        // Version simplifiée :
        lifecycleScope.launch(Dispatchers.IO) {
            val geocoder = android.location.Geocoder(requireContext(), java.util.Locale.getDefault())
            try {
                val results = geocoder.getFromLocationName(query, 1)
                if (results != null && results.isNotEmpty()) {
                    val loc = results[0]
                    savedAddressViewModel.addFavorite(shortcutName, loc.getAddressLine(0)?:query, loc.latitude, loc.longitude)
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

        // On ajoute une marge
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
                        currentAddressName, // L'adresse texte
                        currentDestination!!.latitude,
                        currentDestination!!.longitude
                    )

                    // Feedback visuel
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

        // Ici on pourra ajouter plus tard une ListView pour les Favoris

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
                val geocoder = android.location.Geocoder(requireContext(), java.util.Locale.getDefault())
                val myPos = try { map?.myLocation } catch (e: Exception) { null }

                var results: MutableList<android.location.Address>? = null

                // STRATÉGIE 1 : Recherche locale (Zone 50km)
                if (myPos != null) {
                    val lat = myPos.latitude
                    val lng = myPos.longitude
                    // Tentative standard restreinte à la zone
                    try {
                        results = geocoder.getFromLocationName(query, 1, lat - 0.4, lng - 0.4, lat + 0.4, lng + 0.4)
                    } catch (e: Exception) {}

                    // STRATÉGIE 2 : Le "Hack" Ville (Si rien trouvé)
                    // Si on cherche un magasin (ex: Aldi) sans préciser la ville, ça échoue souvent.
                    // On récupère le nom de ta ville actuelle et on l'ajoute.
                    if (results.isNullOrEmpty()) {
                        try {
                            // On demande à Google : "Dans quelle ville je suis ?"
                            val myAddressInfo = geocoder.getFromLocation(lat, lng, 1)
                            if (myAddressInfo != null && myAddressInfo.isNotEmpty()) {
                                val myCity = myAddressInfo[0].locality // Ex: "Pirey"
                                val newQuery = "$query $myCity"

                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Tentative avec : $newQuery", Toast.LENGTH_SHORT).show()
                                }
                                results = geocoder.getFromLocationName(newQuery, 1)
                            }
                        } catch (e: Exception) {}
                    }
                }

                // STRATÉGIE 3 : Recherche mondiale (Dernier recours)
                if (results.isNullOrEmpty()) {
                    results = geocoder.getFromLocationName(query, 1)
                }

                // --- TRAITEMENT DU RÉSULTAT ---
                if (results != null && results.isNotEmpty()) {
                    val location = results[0]
                    val destinationCoords = "${location.latitude},${location.longitude}"

                    currentDestination = LatLng(location.latitude, location.longitude)
                    currentAddressName = location.featureName ?: query
                    val fullAddress = location.getAddressLine(0) ?: query

                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Go : $fullAddress", Toast.LENGTH_SHORT).show()
                        startNavigationTo(destinationCoords)

                        // Reset bouton favori
                        binding.btnSaveFavorite.setImageResource(android.R.drawable.btn_star_big_off)
                        binding.btnSaveFavorite.tag = "unsaved"

                        // On cache le clavier et la recherche pour voir la carte
                        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
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

                    // --- PARTIE CARTE (EXISTANTE) ---
                    if (map != null && isTrackingMode) {
                        val currentLatLng = LatLng(location.latitude, location.longitude)
                        val cameraUpdate = CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(currentLatLng)
                                .zoom(18f)
                                .bearing(location.bearing)
                                .tilt(0f)
                                .build()
                        )
                        map?.animateCamera(cameraUpdate)
                    }

                    // --- PARTIE COMPTEUR KILOMÉTRIQUE (NOUVEAU) ---
                    if (lastLocation != null) {
                        // Calcul de la distance parcourue depuis la dernière seconde (en mètres)
                        val distanceInMeters = location.distanceTo(lastLocation!!)

                        // On ignore les mouvements minuscules (bruit GPS à l'arrêt)
                        // Si on a bougé de plus de 2 mètres
                        if (distanceInMeters > 2) {
                            // Conversion en Km (ex: 500m = 0.5km)
                            val distanceInKm = distanceInMeters / 1000.0

                            // On demande au ViewModel d'ajouter ça au total
                            // Note: Il faudra créer cette fonction 'addDistance' juste après
                            profileViewModel.addDistanceToProfile(distanceInKm)
                        }
                    }

                    // On met à jour la dernière position connue
                    lastLocation = location
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
                        val route = routes.getJSONObject(0)

                        // 1. DESSIN (Identique)
                        val points = route.getJSONObject("overview_polyline").getString("points")
                        val decodedPath = PolylineDecoder.decode(points)

                        // 2. EXTRACTION INFOS (Nouveau !)
                        val legs = route.getJSONArray("legs")
                        val leg = legs.getJSONObject(0)
                        val distanceText = leg.getJSONObject("distance").getString("text") // ex: "145 km"
                        val durationText = leg.getJSONObject("duration").getString("text") // ex: "2 hours 10 mins"

                        withContext(Dispatchers.Main) {
                            drawRouteOnMap(decodedPath)

                            // Afficher la CardView
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