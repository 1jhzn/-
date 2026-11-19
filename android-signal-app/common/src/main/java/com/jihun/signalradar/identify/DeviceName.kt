package com.jihun.signalradar.identify

/**
 * The "who is this?" part of a Bluetooth reading.
 *
 * A phone can tell three different things about a nearby Bluetooth device: the name the
 * device puts into its own advertisement, the name the phone has cached for it (which is
 * also what a paired device shows), and, only when the device allows a connection, the
 * name stored on the device itself. This object holds the wording and the small decoders
 * so they are unit-tested instead of guessed at on the phone.
 *
 * Pure Kotlin: no Android types.
 */
object DeviceName {

    const val NO_NAME_TITLE = "이름 없는 블루투스 기기"

    /** What a device that refuses the GATT name read is told, honestly. */
    const val BLOCKED_MESSAGE = "이 기기는 보안상 상세 이름 공개를 막아 뒀어요"

    /** Connection status wording for the list row and the detail screen. */
    fun connectedBadge(isConnected: Boolean): String? =
        if (isConnected) "🔗 내 폰 연결됨" else null

    fun bondedBadge(isBonded: Boolean): String? =
        if (isBonded) "📌 내 등록 기기" else null

    /** One compact badge line: the live connection wins, the pairing shows next to it. */
    fun badgeText(isConnected: Boolean, isBonded: Boolean): String? {
        val parts = listOfNotNull(connectedBadge(isConnected), bondedBadge(isBonded))
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** "📢 방송명: 지훈의 AirPods", or null when the device keeps its name to itself. */
    fun broadcastLabel(name: String?): String? =
        clean(name)?.let { "📢 방송명: $it" }

    /** The honest footnote under a broadcast name: it is a hint, not proof. */
    fun ownerHint(name: String?): String? {
        clean(name) ?: return null
        return "기기가 스스로 알려 준 이름이에요 — 누구 것인지 짐작할 수 있어요."
    }

    /** Device names arrive as UTF-8 and may carry padding, newlines or control bytes. */
    fun decode(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        }
        return clean(text)
    }

    /** Trim, drop control characters, and treat an empty result as "no name". */
    fun clean(raw: String?): String? {
        if (raw == null) return null
        val builder = StringBuilder(raw.length)
        for (char in raw) {
            when {
                char == '\u0000' -> {}
                char.code < 0x20 -> builder.append(' ')
                else -> builder.append(char)
            }
        }
        val text = builder.toString().trim().replace(Regex("\\s+"), " ")
        return text.takeIf { it.isNotEmpty() }
    }

    /**
     * Which of the two names wins for the row title. A name the device broadcasts is the
     * best ownership hint; the phone's cached name (from pairing) is the fallback.
     */
    fun bestName(advertised: String?, system: String?): String? =
        clean(advertised) ?: clean(system)

    fun title(advertised: String?, system: String?, kind: String?): String =
        bestName(advertised, system) ?: kind ?: NO_NAME_TITLE

    /** True when the name came from the device's own advertisement rather than the phone. */
    fun isBroadcasting(advertised: String?): Boolean = clean(advertised) != null
}
