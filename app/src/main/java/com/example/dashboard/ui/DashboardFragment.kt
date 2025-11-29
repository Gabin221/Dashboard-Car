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
                Toast.makeText(context, "Déjà enregistré ou pas de destination", Toast.LENGTH_SHORT).show()
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

                // --- AMÉLIORATION : RECHERCHE LOCALE ---
                // On récupère la position actuelle pour aider le Geocoder
                val myPos = try { map?.myLocation } catch (e: Exception) { null }

                val results: MutableList<android.location.Address>?

                if (myPos != null) {
                    // On définit une zone de recherche (environ 50km autour de toi)
                    // 1 degré de latitude ~= 111km. Donc 0.5 ~= 55km.
                    val lowerLeftLat = myPos.latitude - 0.4
                    val lowerLeftLong = myPos.longitude - 0.4
                    val upperRightLat = myPos.latitude + 0.4
                    val upperRightLong = myPos.longitude + 0.4

                    // Recherche avec contrainte de zone (API Android moderne)
                    // Note: Si tu es sur une vieille version Android, cette méthode spécifique peut ne pas exister
                    // mais getFromLocationName avec 4 doubles fonctionne depuis l'API 1.
                    results = geocoder.getFromLocationName(query, 5, lowerLeftLat, lowerLeftLong, upperRightLat, upperRightLong)
                } else {
                    // Fallback si pas de GPS
                    results = geocoder.getFromLocationName(query, 5)
                }

                if (results != null && results.isNotEmpty()) {
                    // On prend le premier résultat
                    val location = results[0]
                    val destinationCoords = "${location.latitude},${location.longitude}"

                    // On sauvegarde les infos pour le bouton "Favori"
                    currentDestination = LatLng(location.latitude, location.longitude)
                    currentAddressName = location.featureName ?: query // "Aldi" ou l'adresse
                    val fullAddress = location.getAddressLine(0) ?: query

                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Trouvé : $fullAddress", Toast.LENGTH_SHORT).show()

                        // Si c'est un résultat vague (ex: juste une ville), on zoome dessus
                        // Sinon on trace la route
                        startNavigationTo(destinationCoords)

                        // On prépare le bouton favori (Étoile vide par défaut)
                        binding.btnSaveFavorite.setImageResource(android.R.drawable.btn_star_big_off)
                        binding.btnSaveFavorite.tag = "unsaved" // Petit marqueur pour savoir l'état
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Introuvable dans le coin 😕", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Erreur recherche: ${e.message}", Toast.LENGTH_LONG).show()
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
                                .tilt(60f)
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