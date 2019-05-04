package com.trangar.wifiscraper

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import android.net.wifi.ScanResult

class Database(
    context: Context,
    factory: SQLiteDatabase.CursorFactory?,
    filename: String
) :
    SQLiteOpenHelper(context, filename, factory, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
    CREATE TABLE location (
        ID INTEGER NOT NULL PRIMARY KEY,
        latitude DOUBLE NOT NULL,
        longitude DOUBLE NOT NULL,
        altitude DOUBLE NOT NULL,
        speed DOUBLE NOT NULL,
        bearing DOUBLE NOT NULL,
        horizontal_accuracy DOUBLE NOT NULL,
        vertical_accuracy DOUBLE NOT NULL,
        speed_accuracy_meters_per_second DOUBLE NOT NULL,
        bearing_accuracy_degrees DOUBLE NOT NULL
    )
    """
        )
        db.execSQL(
            """
    CREATE TABLE wifi_point (
        ID INTEGER NOT NULL PRIMARY KEY,
        location_id INTEGER NOT NULL REFERENCES location(ID),
        ssid TEXT NOT NULL,
        bssid TEXT NOT NULL,
        capabilities TEXT NOT NULL,
        level_dbm INTEGER NOT NULL,
        frequency_20mhz INTEGER NOT NULL,
        channel_width_mhz INTEGER NOT NULL,
        center_freq_0 INTEGER NOT NULL,
        center_freq_1 INTEGER NOT NULL
    )
    """
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS wifi_point")
        db.execSQL("DROP TABLE IF EXISTS location")
        onCreate(db)
    }

    fun enterLocation(location: Location): Long {
        val values = ContentValues()
        values.put("latitude", location.latitude)
        values.put("longitude", location.longitude)
        values.put("altitude", location.altitude)
        values.put("speed", location.speed)
        values.put("bearing", location.bearing)
        values.put("horizontal_accuracy", location.accuracy)
        values.put("vertical_accuracy", location.verticalAccuracyMeters)
        values.put("speed_accuracy_meters_per_second", location.speedAccuracyMetersPerSecond)
        values.put("bearing_accuracy_degrees", location.bearingAccuracyDegrees)

        val db = this.writableDatabase

        val id = db.insert("location", null, values)
        db.close()

        return id
    }

    fun enterWifiPoint(point: ScanResult, location_id: Long) {
        val values = ContentValues()
        values.put("location_id", location_id)
        values.put("ssid", point.SSID)
        values.put("bssid", point.BSSID)
        values.put("capabilities", point.capabilities)
        values.put("level_dbm", point.level)
        values.put("frequency_20mhz", point.frequency)
        values.put(
            "channel_width_mhz", when (point.channelWidth) {
                ScanResult.CHANNEL_WIDTH_20MHZ -> 20
                ScanResult.CHANNEL_WIDTH_40MHZ -> 40
                ScanResult.CHANNEL_WIDTH_80MHZ -> 80
                ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
                ScanResult.CHANNEL_WIDTH_160MHZ -> 160
                else -> 0
            }
        )
        values.put("center_freq_0", point.centerFreq0)
        values.put("center_freq_1", point.centerFreq1)

        val db = this.writableDatabase
        db.insert("wifi_point", null, values)
        db.close()
    }

    fun countEntries(): CountResult {
        val db = this.readableDatabase
        val cursor = db.rawQuery("""
            SELECT
                (SELECT COUNT(*) FROM location) AS locationCount,
                (SELECT COUNT(*) FROM wifi_point) AS wifiCount
        """, null)
        cursor.moveToFirst()
        val locationCount = cursor.getInt(0)
        val wifiCount = cursor.getInt(1)
        cursor.close()

        return CountResult (
            locationCount,
            wifiCount
        )
    }

    class CountResult(var locationCount: Int, var wifiCount: Int) {
    }
}
