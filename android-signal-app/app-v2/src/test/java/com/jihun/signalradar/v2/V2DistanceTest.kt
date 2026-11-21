package com.jihun.signalradar.v2

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.jihun.signalradar.R
import com.jihun.signalradar.SignalRadarActivity
import com.jihun.signalradar.SignalRepository
import com.jihun.signalradar.identify.Classifier
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * 전파 감지기 v2.4 — 지훈: "거리도 버튼을 누르면 예상을 볼수있게 해줘".
 *
 * The `📏 예상 거리` switch, checked the way it is used:
 *
 *  - off is the v2.3 screen (steps only), and the switch is off on a fresh install
 *  - pressing the chip, a row's distance pill, the finder button or the detail card's pill
 *    turns the estimate on everywhere at once and remembers it
 *  - the estimate is the shared classifier's number written beside the step
 *    (`매우 가까움 · 50cm 이내`), never a made-up figure
 *  - the finder and the detail card show it while it is on, and hide it again when off
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], qualifiers = "w360dp-h780dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V2DistanceTest {

    private fun grantScanPermissions() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            "android.permission.NEARBY_WIFI_DEVICES"
        )
    }

    private fun bleSignal(
        rssi: Int = -50,
        title: String = "지훈의 AirPods",
        address: String = "F8:1E:DF:12:34:56"
    ) = Signal(
        id = "ble:$address",
        source = SignalSource.BLE,
        title = title,
        vendor = "애플",
        kind = "에어팟",
        detail = "에어팟 · 서비스 배터리",
        address = address,
        rssi = rssi,
        firstSeen = System.currentTimeMillis() - 30_000,
        lastSeen = System.currentTimeMillis(),
        smoothedRssi = rssi.toFloat(),
        txPower = -52,
        broadcastName = title,
        isConnected = true,
        isBonded = true
    )

    private fun repoOf(activity: V2RadarActivity): SignalRepository {
        val field = SignalRadarActivity::class.java.getDeclaredField("repo")
        field.isAccessible = true
        return field.get(activity) as SignalRepository
    }

    private fun prefs(activity: V2RadarActivity) =
        activity.getSharedPreferences("signal_radar", Context.MODE_PRIVATE)

    private fun refresh(seconds: Long = 1L) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(seconds, TimeUnit.SECONDS)
    }

    private fun measure(root: View, widthDp: Int, heightDp: Int) {
        ShadowLooper.idleMainLooper()
        val metrics = root.resources.displayMetrics
        val w = (widthDp * metrics.density).toInt()
        val h = (heightDp * metrics.density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, w, h)
        ShadowLooper.idleMainLooper()
    }

    private fun showDetails(activity: V2RadarActivity, signal: Signal) {
        val method = SignalRadarActivity::class.java
            .getDeclaredMethod("showDetails", Signal::class.java)
        method.isAccessible = true
        method.invoke(activity, signal)
    }

    // ------------------------------------------------------------- the switch

    @Test
    fun the_switch_starts_off_so_the_screen_still_reads_as_steps_only() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(listOf(bleSignal(rssi = -59)))
        refresh()

        val chip = activity.findViewById<TextView>(R.id.chipDistance)
        assertEquals("📏 예상 거리", chip.text.toString())
        assertFalse("nothing may be stored as on", V2Distance.isOn(prefs(activity)))
        assertEquals(
            "전체 1",
            activity.findViewById<TextView>(R.id.chipAll).text.toString()
        )

        val row = firstRow(activity)
        val badge = row.findViewById<TextView>(R.id.itemDistance)
        assertEquals("매우 가까움", badge.text.toString())
        assertFalse("a step only, no metres", badge.text.contains("m"))
        assertEquals(
            "매우 가까움",
            activity.findViewById<TextView>(R.id.v2CalloutStep).text.toString()
        )
        controller.pause().stop().destroy()
    }

    @Test
    fun pressing_the_chip_writes_the_estimate_on_the_rows_and_the_top_card() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(listOf(bleSignal(rssi = -59)))
        refresh()

        activity.findViewById<View>(R.id.chipDistance).performClick()
        refresh()

        // The switch is on, saved, and drawn as a pressed chip.
        assertTrue(V2Distance.isOn(prefs(activity)))
        assertEquals(
            activity.getColor(R.color.v2_sheet),
            activity.findViewById<TextView>(R.id.chipDistance).currentTextColor
        )

        // The row's badge and the top card both carry the estimate.
        val badge = firstRow(activity).findViewById<TextView>(R.id.itemDistance)
        assertEquals("매우 가까움 · 약 0.9m", badge.text.toString())
        assertEquals(
            "매우 가까움 · 약 0.9m",
            activity.findViewById<TextView>(R.id.v2CalloutStep).text.toString()
        )

        // Pressing it again goes back to the plain step, and to the unpressed chip.
        activity.findViewById<View>(R.id.chipDistance).performClick()
        refresh()
        assertFalse(V2Distance.isOn(prefs(activity)))
        assertEquals(
            "매우 가까움",
            firstRow(activity).findViewById<TextView>(R.id.itemDistance).text.toString()
        )
        assertEquals(
            activity.getColor(R.color.v2_ink_soft),
            activity.findViewById<TextView>(R.id.chipDistance).currentTextColor
        )
        controller.pause().stop().destroy()
    }

    @Test
    fun the_estimate_is_the_shared_classifier_number_beside_the_step() {
        // Under half a metre, the wording 지훈 sees on the brief's first example.
        assertEquals("50cm 이내", Classifier.distanceLabel(-40, SignalSource.BLE, -52, null))
        assertEquals(
            "매우 가까움 · 50cm 이내",
            V2Distance.badge(bleSignal(rssi = -40), true)
        )
        // v2.8: the tuned indoor model writes a shorter distance than the old free-space one —
        // -59 dBm from a device advertising -52 dBm at 1 m is now under a metre, not 1.8 m.
        assertEquals("매우 가까움 · 약 0.9m", V2Distance.badge(bleSignal(rssi = -59), true))
        assertEquals("가까움 · 약 2.4m", V2Distance.badge(bleSignal(rssi = -72), true))
        // Off is the step word alone, exactly as v2.3 wrote it.
        assertEquals("가까움", V2Distance.badge(bleSignal(rssi = -72), false))
        // A base station has no distance worth writing, so no badge and no estimate.
        val cell = bleSignal().copy(source = SignalSource.CELL, address = "1234")
        assertEquals(null, V2Distance.badge(cell, true))
        assertEquals(null, V2Distance.metresOf(cell))
        // The finder's line is the estimate with its own label.
        assertEquals("📏 예상 50cm 이내", V2Distance.finderLine("50cm 이내"))
        assertEquals(null, V2Distance.finderLine(null))
    }

    @Test
    fun the_switch_is_remembered_until_the_next_launch() {
        grantScanPermissions()
        val first = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = first.get()
        activity.findViewById<View>(R.id.chipDistance).performClick()
        refresh()
        first.pause().stop().destroy()

        // A fresh launch of the app reads the saved switch before the first row is drawn.
        val second = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val again = second.get()
        repoOf(again).upsert(listOf(bleSignal(rssi = -59)))
        refresh()
        assertTrue(V2Distance.isOn(prefs(again)))
        assertEquals(
            "매우 가까움 · 약 0.9m",
            firstRow(again).findViewById<TextView>(R.id.itemDistance).text.toString()
        )
        second.pause().stop().destroy()
    }

    @Test
    fun the_distance_pill_is_its_own_tap_target_inside_a_row() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(listOf(bleSignal(rssi = -59)))
        refresh()

        // Pressing the pill turns the estimate on…
        firstRow(activity).findViewById<View>(R.id.itemDistance).performClick()
        refresh()
        assertTrue("the pill must flip the switch", V2Distance.isOn(prefs(activity)))
        assertTrue(
            firstRow(activity).findViewById<TextView>(R.id.itemDistance).text.contains("약")

        )

        // …and it does not open the finder, which is what the rest of the row still does.
        assertEquals(View.GONE, activity.findViewById<View>(R.id.orbPage).visibility)
        firstRow(activity).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.orbPage).visibility)
        activity.findViewById<View>(R.id.orbClose).performClick()
        controller.pause().stop().destroy()
    }

    // ------------------------------------------------------------- the finder

    @Test
    fun the_finder_shows_the_estimate_and_can_switch_it_itself() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(listOf(bleSignal(rssi = -44)))
        refresh()

        // Off: the finder is the big step word and nothing under it.
        activity.findViewById<View>(R.id.v2Callout).performClick()
        refresh()
        assertEquals("매우 가까움", activity.findViewById<TextView>(R.id.orbStep).text.toString())
        assertEquals(
            View.GONE,
            activity.findViewById<View>(R.id.orbDistance).visibility
        )

        // Its own 📏 button turns the estimate on, without leaving the finder.
        val toggle = activity.findViewById<TextView>(R.id.orbDistanceToggle)
        assertEquals("📏 예상 거리", toggle.text.toString())
        toggle.performClick()
        refresh()
        val line = activity.findViewById<TextView>(R.id.orbDistance)
        assertEquals(View.VISIBLE, line.visibility)
        assertEquals(
            "📏 예상 " + V2Calibration.metresText(bleSignal(rssi = -44)),
            line.text.toString()
        )
        assertTrue(
            "the finder must say a number, not the step again",
            line.text.toString().startsWith("📏 예상 ") && !line.text.contains("매우 가까움")
        )

        // Pressing it again hides the line and keeps the switch off in the saved setting.
        toggle.performClick()
        refresh()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.orbDistance).visibility)
        assertFalse(V2Distance.isOn(prefs(activity)))
        controller.pause().stop().destroy()
    }

    // -------------------------------------------------------- the detail card

    @Test
    fun the_detail_card_switches_the_estimate_from_its_own_pill() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val signal = bleSignal(rssi = -59)
        repoOf(activity).upsert(listOf(signal))

        showDetails(activity, signal)
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)
        val toggle = dialog.findViewById<TextView>(R.id.detailDistanceToggle)
        assertEquals(V2RadarActivity.DETAIL_DISTANCE_SHOW, toggle.text.toString())
        assertTrue(
            dialog.findViewById<TextView>(R.id.detailBody).text
                .contains("대략 거리: 매우 가까움")
        )

        // The card's pill turns it on, and the card comes back with the number written in.
        toggle.performClick()
        refresh()
        assertTrue(V2Distance.isOn(prefs(activity)))
        val reopened = ShadowDialog.getLatestDialog()
        assertTrue(reopened.isShowing)
        assertEquals(
            V2RadarActivity.DETAIL_DISTANCE_HIDE,
            reopened.findViewById<TextView>(R.id.detailDistanceToggle).text.toString()
        )
        val body = reopened.findViewById<TextView>(R.id.detailBody).text.toString()
        assertTrue(body, body.contains("대략 거리: 매우 가까움 · 약 0.9m"))
        assertTrue("the card must say it is an estimate", body.contains("어림값"))
        reopened.dismiss()
        controller.pause().stop().destroy()
    }

    // ---------------------------------------------------------------- layout

    @Test
    fun the_longer_badge_and_the_top_card_still_fit_a_320dp_phone() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(
            listOf(
                bleSignal(rssi = -40, title = "아주아주아주아주아주 긴 이름의 블루투스 이어폰"),
                bleSignal(rssi = -59, address = "AA:BB:CC:DD:EE:FF", title = "민수의 Galaxy Buds")
            )
        )
        refresh()
        activity.findViewById<View>(R.id.chipDistance).performClick()
        refresh()

        val root = activity.findViewById<View>(android.R.id.content)
        for (widthDp in listOf(320, 360, 412)) {
            measure(root, widthDp, 780)
            val row = firstRow(activity)
            val badge = row.findViewById<TextView>(R.id.itemDistance)
            val title = row.findViewById<TextView>(R.id.itemTitle)
            assertNotNull("the badge must be laid out", badge.layout)
            assertTrue(
                "width=${widthDp}dp the badge must stay inside the row: right=${badge.right}",
                badge.right <= row.width
            )
            assertTrue(
                "width=${widthDp}dp the name needs room beside the badge: title=${title.width}",
                title.width > 0
            )
            assertTrue(
                "width=${widthDp}dp the badge takes at most two lines: ${badge.layout.lineCount}",
                badge.layout.lineCount <= 2
            )

            // The chip row's own switch fits the sheet at every width.
            val chip = activity.findViewById<TextView>(R.id.chipDistance)
            val needed = chip.paint.measureText(chip.text.toString()) +
                chip.paddingLeft + chip.paddingRight
            assertTrue(
                "width=${widthDp}dp chip width=${chip.width} needs=$needed",
                chip.width + 1 >= needed
            )

            // The top card's pill fits too, with the title still readable.
            val step = activity.findViewById<TextView>(R.id.v2CalloutStep)
            assertTrue(
                "width=${widthDp}dp the pill must stay inside the callout: right=${step.right}",
                step.right <= activity.findViewById<View>(R.id.v2Callout).width
            )
            assertTrue(
                "width=${widthDp}dp the callout pill takes at most two lines: ${step.layout.lineCount}",
                step.layout.lineCount <= 2
            )
        }
        controller.pause().stop().destroy()
    }

    /** The first row the v2 list actually drew. */
    private fun firstRow(activity: V2RadarActivity): View {
        val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(
            R.id.v2SignalList
        )
        assertNotNull("the list must draw a row", list.getChildAt(0))
        return list.getChildAt(0)
    }
}
