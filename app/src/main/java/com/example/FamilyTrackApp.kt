package com.example

import android.app.Application
import org.osmdroid.config.Configuration
import java.io.File

class FamilyTrackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Configure osmdroid cleanly for Android 11-14 without legacy external storage permissions
        val osmConfig = Configuration.getInstance()
        osmConfig.userAgentValue = packageName
        val basePath = File(cacheDir, "osmdroid")
        basePath.mkdirs()
        osmConfig.osmdroidBasePath = basePath
        osmConfig.osmdroidTileCache = File(basePath, "tiles").apply { mkdirs() }
    }
}
