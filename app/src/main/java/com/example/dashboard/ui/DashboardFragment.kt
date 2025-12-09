package com.example.dashboard.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.location.Location
import androidx.fragment.app.viewModels
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
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashboardFragment : Fragment(), OnMapReadyCallback {
    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private var map: GoogleMap? = null

    private var lastApiCallTime: Long = 0
    private var lastApiCallLocation: Location? = null
    private var currentSpeedKmH: Float = 0f

    private var currentFavoritesList: List<com.example.dashboard.data.SavedAddress> = emptyList()
    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient
    private var locationCallback: com.google.android.gms.location.LocationCallback? = null
    private var isTrackingMode = true
    private val profileViewModel: ProfileViewModel by viewModels()
    private var lastLocation: Location? = null
    private val savedAddressViewModel: SavedAddressViewModel by viewModels()
    private var currentDestination: LatLng? = null
    private var currentAddressName: String = ""

    private val navigationHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isNavigationActive = false

    private val navigationRunnable = object : Runnable {
        override fun run() {
            if (isNavigationActive && currentDestinationCoords != null) {
                val myLoc = try { map?.myLocation } catch (e: Exception) { null }

                if (myLoc != null) {
                    val currentTime = System.currentTimeMillis()

                    if (lastApiCallLocation == null) {
                        performRefresh(myLoc)
                    } else {
                        val distanceTraveled = myLoc.distanceTo(lastApiCallLocation!!)
                        val timeElapsed = currentTime - lastApiCallTime

                        val (distThreshold, timeThreshold) = when {
                            currentSpeedKmH < 50 -> Pair(200, 60_000L)
                            currentSpeedKmH < 90 -> Pair(1000, 90_000L)
                            else -> Pair(3000, 180_000L)
                        }

                        if (distanceTraveled > distThreshold || timeElapsed > timeThreshold) {
                            if (distanceTraveled > 20) {
                                android.util.Log.d("GPS_SMART", "Refresh: Vitesse=$currentSpeedKmH km/h, Dist=$distanceTraveled m")
                                performRefresh(myLoc)
                            }
                        }
                    }
                }

                navigationHandler.postDelayed(this, 5000L)
            }
        }
    }

    private fun performRefresh(location: Location) {
        startNavigationTo(currentDestinationCoords!!)

        lastApiCallTime = System.currentTimeMillis()
        val locSnapshot = Location("memory")
        locSnapshot.latitude = location.latitude
        locSnapshot.longitude = location.longitude
        lastApiCallLocation = locSnapshot
    }

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

        lifecycleScope.launch { ObdManager.currentSpeed.collect { binding.tvSpeed.text = it.toString() } }
        lifecycleScope.launch { ObdManager.currentRpm.collect { binding.tvRpm.text = it.toString() } }
        lifecycleScope.launch { ObdManager.currentData1.collect { binding.tvData1.text = it.toString() } }
        lifecycleScope.launch { ObdManager.currentData2.collect { binding.tvData2.text = it.toString() } }

        binding.btnSaveCurrent.setOnClickListener {
            val lastLocation = map?.myLocation
            if (lastLocation == null) {
                Toast.makeText(context, "Position GPS introuvable. Activez le suivi.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showSaveLocationDialog(lastLocation.latitude, lastLocation.longitude)
        }

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

        lifecycleScope.launch {
            savedAddressViewModel.savedAddresses.collect { savedList ->
                currentFavoritesList = savedList
            }
        }

        binding.btnOpenFavorites.setOnClickListener {
            showFavoritesListDialog()
        }

        startObdConnection()
    }

    private fun showFavoritesListDialog() {
        if (currentFavoritesList.isEmpty()) {
            Toast.makeText(context, "Aucun favori enregistré.", Toast.LENGTH_SHORT).show()
            return
        }

        val names = currentFavoritesList.map { it.name }.toTypedArray()

        val listView = android.widget.ListView(requireContext())
        val adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, names)
        listView.adapter = adapter

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Mes Destinations")
            .setView(listView)
            .setNegativeButton("Fermer", null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedFav = currentFavoritesList[position]

            binding.etSearch.setText(selectedFav.name)
            val destCoords = "${selectedFav.latitude},${selectedFav.longitude}"
            startNavigationTo(destCoords)

            dialog.dismiss()
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val selectedFav = currentFavoritesList[position]

            val options = arrayOf("Modifier le nom", "Supprimer")
            AlertDialog.Builder(requireContext())
                .setTitle("Gérer '${selectedFav.name}'")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> showEditFavoriteDialog(selectedFav)
                        1 -> deleteFavoriteConfirm(selectedFav)
                    }
                }
                .show()

            true
        }

        dialog.show()
    }

    private fun deleteFavoriteConfirm(item: com.example.dashboard.data.SavedAddress) {
        AlertDialog.Builder(requireContext())
            .setTitle("Supprimer ${item.name} ?")
            .setPositiveButton("Oui") { _, _ ->
                savedAddressViewModel.deleteFavorite(item)
                Toast.makeText(context, "Supprimé !", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Non", null)
            .show()
    }

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
        input.setText("Position Sauvée ${SimpleDateFormat("HH:mm").format(Date())}")

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Nommer le Favori")
            .setView(input)
            .setPositiveButton("Enregistrer") { _, _ ->
                val name = input.text.toString().trim()
                val address = input.text.toString()
                if (name.isNotBlank()) {
                    savedAddressViewModel.addFavorite(name, address, lat, lon)
                    Toast.makeText(context, "$name ajouté aux favoris.", Toast.LENGTH_SHORT).show()
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
                            map!!.cameraPosition.bearing
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
                        currentSpeedKmH = location.speed * 3.6f
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

            val distanceMeters = currentLocation.distanceTo(destLoc)

            val distKm = distanceMeters / 1000.0
            binding.tvTripDistance.text = String.format("%.1f km", distKm)

            val speed = if (currentLocation.hasSpeed() && currentLocation.speed > 2) currentLocation.speed else 13.8f
            val timeSeconds = distanceMeters / speed
            val minutes = (timeSeconds / 60).toInt()

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

            binding.tvTripEnd.text = arrivalFormatted

            if (distanceMeters < 50) {
                binding.tvTripTime.text = "Arrivé !"
                binding.tvTripTime.setTextColor(Color.GREEN)
            }
        }
    }

    private fun startNavigationTo(destination: String) {
        currentDestinationCoords = destination

        if (!isNavigationActive) {
            isNavigationActive = true
            navigationHandler.post(navigationRunnable)
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
