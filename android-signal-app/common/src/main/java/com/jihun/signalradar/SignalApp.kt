package com.jihun.signalradar

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.jihun.signalradar.identify.VendorDb

class SignalApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        // 40k OUI rows + 4k Bluetooth company ids: read them off the main thread.
        Thread {
            VendorDb.ensureLoaded(this)
        }.start()
    }
}
