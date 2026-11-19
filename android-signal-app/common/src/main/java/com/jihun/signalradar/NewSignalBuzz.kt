package com.jihun.signalradar

/**
 * Decides when a "새 신호가 잡혔어요" buzz may play.
 *
 * 지훈's phone used to buzz for 170 ms at full power on every device that turned up, and a
 * room full of them buzzed again and again — "너무 진동이 심하니까 줄여주고". So the alert is
 * now one faint 18 ms tap, and [allow] keeps it to at most one tap per [MIN_GAP_MS] (10 s)
 * however many devices arrive at once: the banner and the 🚨 counter still show every one of
 * them, only the motor is spared.
 *
 * Pure Kotlin on purpose, so the throttle is unit-tested without a phone.
 */
class NewSignalBuzz(private val minGapMs: Long = MIN_GAP_MS) {

    private var lastAt: Long? = null

    /** True when a buzz may play at [now]; a true answer books that moment. */
    fun allow(now: Long): Boolean {
        val last = lastAt
        if (last != null && now - last < minGapMs) return false
        lastAt = now
        return true
    }

    /** Start over, e.g. after the user saved a new baseline on purpose. */
    fun reset() {
        lastAt = null
    }

    companion object {
        /** 18 ms: a tap you notice in a pocket, not a buzz. */
        const val DURATION_MS = 18L

        /** 1 of 255 — the faintest amplitude the vibration motor accepts. */
        const val AMPLITUDE = 1

        /** At most one buzz every ten seconds. */
        const val MIN_GAP_MS = 10_000L
    }
}
