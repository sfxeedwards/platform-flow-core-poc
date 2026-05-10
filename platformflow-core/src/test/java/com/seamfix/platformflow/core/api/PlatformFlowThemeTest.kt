package com.seamfix.platformflow.core.api

import com.seamfix.platformflow.core.ui.TestComponentHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [PlatformFlowTheme], [Color], and [ButtonStyle] against
 * SDK Architecture §18.1, plus the `ComponentHost.theme` default-getter
 * wiring (§18.2).
 */
class PlatformFlowThemeTest {

    // ── Color value class ───────────────────────────────────────────

    @Test
    fun color_accepts_full_argb_range() {
        // Boundary values must construct without throwing.
        Color(0L)
        Color(0xFFFFFFFF)
        Color(0xFF6200EE)
    }

    @Test
    fun color_rejects_negative_argb() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            Color(-1L)
        }
        assertTrue(
            "error should describe the offending value",
            ex.message?.contains("ARGB") == true,
        )
    }

    @Test
    fun color_rejects_argb_above_32_bit_unsigned_max() {
        assertThrows(IllegalArgumentException::class.java) {
            Color(0x1_0000_0000L)
        }
    }

    @Test
    fun color_argbInt_returns_signed_int_representation() {
        // 0xFF6200EE has the alpha bit set, so as signed Int it's
        // negative. We verify the bit pattern survives the conversion.
        val color = Color(0xFF6200EE)
        assertEquals(0xFF6200EE.toInt(), color.argbInt)
    }

    @Test
    fun color_named_constants_match_expected_argb() {
        assertEquals(0x00000000L, Color.Transparent.argb)
        assertEquals(0xFFFFFFFFL, Color.White.argb)
        assertEquals(0xFF000000L, Color.Black.argb)
    }

    @Test
    fun color_value_class_equality_is_structural() {
        // @JvmInline value class equality is by `argb`.
        val a = Color(0xFF6200EE)
        val b = Color(0xFF6200EE)
        val c = Color(0xFF03DAC5)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    // ── ButtonStyle ─────────────────────────────────────────────────

    @Test
    fun buttonStyle_has_rounded_and_square() {
        // §18.1 spec mentions ROUNDED only; we offer SQUARE as the
        // obvious alternative. Pin both so accidental removals show up.
        val styles = ButtonStyle.entries
        assertTrue(ButtonStyle.ROUNDED in styles)
        assertTrue(ButtonStyle.SQUARE in styles)
    }

    // ── Theme defaults match §18.1 ──────────────────────────────────

    @Test
    fun default_theme_matches_spec_18_1() {
        val theme = PlatformFlowTheme()
        assertEquals(Color(0xFF6200EE), theme.primaryColor)
        assertEquals(Color(0xFF03DAC5), theme.secondaryColor)
        assertEquals(Color.White, theme.backgroundColor)
        assertEquals(Color(0xFFB00020), theme.errorColor)
        assertEquals("sans-serif", theme.fontFamily)
        assertEquals(ButtonStyle.ROUNDED, theme.buttonStyle)
        assertNull(theme.logoDrawable)
    }

    @Test
    fun custom_theme_carries_values_verbatim() {
        val theme = PlatformFlowTheme(
            primaryColor = Color(0xFF003366),
            secondaryColor = Color(0xFFFFCC00),
            backgroundColor = Color(0xFFF5F5F5),
            errorColor = Color(0xFFCC0000),
            fontFamily = "Roboto",
            buttonStyle = ButtonStyle.SQUARE,
            logoDrawable = 123,
        )
        assertEquals(Color(0xFF003366), theme.primaryColor)
        assertEquals(Color(0xFFFFCC00), theme.secondaryColor)
        assertEquals(Color(0xFFF5F5F5), theme.backgroundColor)
        assertEquals(Color(0xFFCC0000), theme.errorColor)
        assertEquals("Roboto", theme.fontFamily)
        assertEquals(ButtonStyle.SQUARE, theme.buttonStyle)
        assertEquals(123, theme.logoDrawable)
    }

    @Test
    fun theme_data_class_supports_copy_for_partial_overrides() {
        val branded = PlatformFlowTheme()
            .copy(primaryColor = Color(0xFF003366), fontFamily = "Lato")

        // Touched fields changed; everything else retained.
        assertEquals(Color(0xFF003366), branded.primaryColor)
        assertEquals("Lato", branded.fontFamily)
        assertEquals(Color(0xFF03DAC5), branded.secondaryColor)
        assertEquals(ButtonStyle.ROUNDED, branded.buttonStyle)
    }

    @Test
    fun theme_equality_is_structural() {
        val a = PlatformFlowTheme(primaryColor = Color(0xFF003366))
        val b = PlatformFlowTheme(primaryColor = Color(0xFF003366))
        val c = PlatformFlowTheme(primaryColor = Color(0xFF000000))
        assertEquals(a, b)
        assertFalse(a == c)
    }

    // ── ComponentHost wiring (§18.2) ────────────────────────────────

    @Test
    fun componentHost_default_theme_is_the_spec_default() {
        // Implementations that don't override ComponentHost.theme should
        // still serve a sensible default — anchors §18.2 backward
        // compatibility.
        val host = TestComponentHost()
        assertEquals(PlatformFlowTheme(), host.theme)
    }

    @Test
    fun componentHost_can_override_theme_with_a_branded_value() {
        val brand = PlatformFlowTheme(
            primaryColor = Color(0xFF003366),
            fontFamily = "Lato",
        )
        val host = object : com.seamfix.platformflow.core.ui.ComponentHost {
            override val context: android.content.Context
                get() = throw UnsupportedOperationException()
            override val theme: PlatformFlowTheme = brand
            override suspend fun showFragment(
                fragment: androidx.fragment.app.Fragment,
            ) = error("unused")
            override suspend fun startActivityForResult(
                intent: android.content.Intent,
            ) = error("unused")
            override suspend fun <T> withLoading(
                message: String,
                block: suspend () -> T,
            ): T = block()
        }
        assertSame(brand, host.theme)
        assertEquals("Lato", host.theme.fontFamily)
    }
}
