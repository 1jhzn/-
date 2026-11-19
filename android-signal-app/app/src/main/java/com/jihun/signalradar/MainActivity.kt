package com.jihun.signalradar

/**
 * The original app: one screen, the live signal list with distance, filters and the
 * direction finder.
 *
 * All of the scanning and rendering lives in [SignalRadarActivity]; this class only says
 * which layout to show, so the "내 주변" app (a separate package, separate APK) behaves
 * exactly the same when it uses the same screen.
 */
class MainActivity : SignalRadarActivity() {

    override val contentLayout: Int = R.layout.activity_main
}
