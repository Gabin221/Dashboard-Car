package com.example.dashboard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.dashboard.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.BLUETOOTH_CONNECT] == true) {
            } else {
                Toast.makeText(this, "Permissions nécessaires pour le Bluetooth/GPS", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAndRequestPermissions()

        val navTarget = intent.getStringExtra("NAV_TARGET")

        // On attend un peu que le NavHost soit prêt
        binding.root.post {
            val navController = androidx.navigation.Navigation.findNavController(this, R.id.nav_host_fragment)

            if (navTarget == "dashboard") {
                navController.navigate(R.id.dashboardFragment) // Utilise l'ID de ton graphe de nav
            } else if (navTarget == "maintenance") {
                navController.navigate(R.id.maintenanceFragment)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val navTarget = intent?.getStringExtra("NAV_TARGET")
        val navController = androidx.navigation.Navigation.findNavController(this, R.id.nav_host_fragment)
        if (navTarget == "dashboard") {
            navController.navigate(R.id.dashboardFragment)
        } else if (navTarget == "maintenance") {
            navController.navigate(R.id.maintenanceFragment)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH)
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}