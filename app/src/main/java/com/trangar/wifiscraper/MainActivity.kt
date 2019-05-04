package com.trangar.wifiscraper

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.provider.Settings
import android.support.v7.app.AlertDialog
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import java.text.DecimalFormat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

const val REQUEST_CODE_START_RECORDING: Int = 1

class MainActivity : AppCompatActivity(), LocationListener {
    //region Private properties

    private var scheduler: ScheduledExecutorService? = null
    private var scheduled: ScheduledFuture<*>? = null
    private var locationManager: LocationManager? = null
    private var wifiManager: WifiManager? = null
    private var didRegisterWifiReceiver: Boolean = false

    private var textView: TextView? = null
    private var btnStartEndRecording: Button? = null

    private var lastKnownLocation: Location? = null
    private var lastKnownScanResults: Array<ScanResult> = arrayOf()
    private var database: Database? = null
    private var old_databases: MutableList<String> = mutableListOf()

    //endregion
    //region Overrides

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        textView = findViewById(R.id.textView)
        btnStartEndRecording = findViewById(R.id.btnStartEndRecording)

        scheduler = Executors.newSingleThreadScheduledExecutor()

        updateText()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>, grantResults: IntArray
    ) {
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            when (requestCode) {
                REQUEST_CODE_START_RECORDING -> startRecording()
                else -> {
                    Log.d("MainActivity", "Unknown request permission code: $requestCode")
                }
            }
        }
    }

    //endregion
    //region Public functions

    fun startStopRecordingClicked(@Suppress("UNUSED_PARAMETER") v: View) {
        if (isRecording()) {
            endRecording()
            btnStartEndRecording?.text = getString(R.string.button_start_recording)
        } else {
            startRecording()
            btnStartEndRecording?.text = getString(R.string.button_end_recording)
        }
        updateText()
    }

    //endregion
    //region Private functions

    private fun updateText() {
        textView?.text = if (isRecording()) {
            if (lastKnownLocation == null) {
                "Waiting for GPS..."
            } else {
                var result = "Recording...\n"
                lastKnownLocation?.let {
                    val format = DecimalFormat("#.#####")
                    result += "Location: ${format.format(it.latitude)} / ${format.format(it.longitude)}\n"
                }
                result += "Found ${lastKnownScanResults.size} WiFi points\n"
                database?.let {
                    val databaseCount = it.countEntries()
                    result += "Database has ${databaseCount.locationCount} locations and ${databaseCount.wifiCount} wifi entries"
                }
                result
            }
        } else {
            "Not recording"
        }
    }

    // if scheduled is not null, we're recording
    private fun isRecording(): Boolean = scheduled != null

    private fun startRecording() {
        Log.d("MainActivity", "Checking permissions")
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE
                ), REQUEST_CODE_START_RECORDING
            )
            return
        }
        Log.d("MainActivity", "Checking GPS enabled")

        if (locationManager?.let { checkGpsEnabled(it) } != true) {
            return
        }

        Log.d("MainActivity", "Checking WiFi enabled")
        if (wifiManager?.let { checkWifiEnabled(it) } != true) {
            return
        }

        if (!didRegisterWifiReceiver) {
            Log.d("MainActivity", "Registering wifi receiver")
            registerWifiReceiver()
            didRegisterWifiReceiver = true
        }

        // make sure there is nothing running
        endRecording()

        assert(scheduled == null)
        Log.d("MainActivity", "Starting scheduler")

        scheduled = scheduler?.scheduleAtFixedRate({
        }, 0, 5, TimeUnit.SECONDS)

        Log.d("MainActivity", "Starting location")
        locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0f, this)

        Log.d("MainActivity", "Starting Scan")
        // Doesn't look like there are any other ways of scanning WIFI at the moment
        @Suppress("DEPRECATION")
        wifiManager?.startScan()
    }

    private fun logEntryInfo() {
        var result = ""
        lastKnownLocation?.let {
            val format = DecimalFormat("#.#####")
            result += "Location: ${format.format(it.latitude)} / ${format.format(it.longitude)}"
        }
        lastKnownScanResults.forEach {
            result += ", ${it.SSID} (${WifiManager.calculateSignalLevel(it.level, 100)}%)"
        }
        Log.d("MainActivity", result)
    }

    private fun saveEntry() {
        Log.d("MainActivity", "saveEntry")
        if (lastKnownLocation == null || lastKnownScanResults.isEmpty()) {
            return
        }
        Log.d("MainActivity", "saveEntry 2")

        if (database == null) {
            database = Database(applicationContext, null, "database.sqlite")
        }
        Log.d("MainActivity", "saveEntry 3")

        database?.let { db ->
            Log.d("MainActivity", "saveEntry 4")
            lastKnownLocation?.let { loc ->
                Log.d("MainActivity", "saveEntry 5")
                val locationId = db.enterLocation(loc)

                lastKnownScanResults.forEach { wifi ->
                    Log.d("MainActivity", "saveEntry 6")
                    db.enterWifiPoint(wifi, locationId)
                }

                Log.d(
                    "MainActivity",
                    "Entered 1 location (id: $locationId) and ${lastKnownScanResults.size} wifi points"
                )
            }
        }
    }

    private fun registerWifiReceiver() {
        val mWifiScanReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    wifiManager?.let {
                        lastKnownScanResults = it.scanResults.toTypedArray()
                        if (lastKnownLocation != null) {
                            saveEntry()
                            logEntryInfo()
                            updateText()
                        }
                    }
                }
            }
        }

        registerReceiver(mWifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
    }

    private fun checkWifiEnabled(wifiManager: WifiManager): Boolean {
        return try {
            if (!wifiManager.isWifiEnabled) {
                AlertDialog.Builder(applicationContext)
                    .setMessage(R.string.wifi_not_enabled)
                    .setPositiveButton(
                        R.string.open_wifi_settings
                    ) { _, _ -> applicationContext.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            true
        } catch (ex: Exception) {
            false
        }
    }

    private fun checkGpsEnabled(lm: LocationManager): Boolean {
        var gpsEnabled = false
        var networkEnabled = false

        try {
            gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (ex: Exception) {
        }

        try {
            networkEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (ex: Exception) {
        }

        return if (!gpsEnabled && !networkEnabled) {
            AlertDialog.Builder(applicationContext)
                .setMessage(R.string.gps_network_not_enabled)
                .setPositiveButton(
                    R.string.open_location_settings
                ) { _, _ -> applicationContext.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            false
        } else {
            true
        }
    }

    private fun endRecording() {
        scheduled?.cancel(false)
        scheduled = null

        lastKnownLocation = null
    }

    //endregion
    //region Location manager callback methods

    override fun onLocationChanged(location: Location?) {
        lastKnownLocation = location
        if (lastKnownScanResults.isNotEmpty()) {
            logEntryInfo()
        }

        updateText()
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    override fun onProviderEnabled(provider: String?) {
    }

    override fun onProviderDisabled(provider: String?) {
    }

    //endregion
}

