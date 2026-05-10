package com.seamfix.platformflow.core.api

/**
 * SDK-owned ARGB color value. Per SDK Architecture §18.1 (where the
 * `Color(0xFF6200EE)` syntax appears).
 *
 * Long-backed so callers can write hex literals directly (`Color(0xFF6200EE)`)
 * — Kotlin parses ARGB literals with the alpha bit set as `Long` because
 * they exceed `Int.MAX_VALUE`. Use [argbInt] when interoperating with
 * Android APIs that take an `Int` color (e.g. `View.setBackgroundColor`).
 *
 * Bit layout: `0xAARRGGBB` — alpha in the high byte, then R/G/B.
 *
 * Type-safe at the SDK boundary; the value class adds no runtime overhead
 * compared to passing `Int` around.
 */
@JvmInline
value class Color(val argb: Long) {

    init {
        require(argb in 0L..0xFFFFFFFFL) {
            "ARGB must be in [0x00000000, 0xFFFFFFFF]; got 0x${argb.toString(16)}"
        }
    }

    /** ARGB packed into a signed `Int`, suitable for `android.graphics.Color` APIs. */
    val argbInt: Int get() = argb.toInt()

    companion object {
        val Transparent: Color = Color(0x00000000)
        val White: Color = Color(0xFFFFFFFF)
        val Black: Color = Color(0xFF000000)
    }
}
