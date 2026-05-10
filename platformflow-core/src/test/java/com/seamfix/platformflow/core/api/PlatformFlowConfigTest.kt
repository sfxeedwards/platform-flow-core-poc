package com.seamfix.platformflow.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [PlatformFlowConfig] (§11.1). The class is a thin holder
 * but the validation block, defaults, and URL normalization all carry
 * runtime contracts that the rest of the SDK relies on.
 */
class PlatformFlowConfigTest {

    // ── Defaults ──────────────────────────────────────────────────────

    @Test
    fun cacheTtl_defaults_to_24_hours_in_minutes() {
        val cfg = PlatformFlowConfig(
            apiBaseUrl = "https://api.example.com/",
            apiKey = "secret",
        )
        assertEquals(60L * 24L, cfg.cacheTtlMinutes)
        assertEquals(PlatformFlowConfig.DEFAULT_CACHE_TTL_MINUTES, cfg.cacheTtlMinutes)
    }

    @Test
    fun theme_defaults_to_reference_PlatformFlowTheme() {
        val cfg = PlatformFlowConfig(
            apiBaseUrl = "https://api.example.com/",
            apiKey = "secret",
        )
        assertEquals(PlatformFlowTheme(), cfg.theme)
    }

    @Test
    fun custom_theme_is_held_by_reference() {
        val theme = PlatformFlowTheme(buttonStyle = ButtonStyle.SQUARE)
        val cfg = PlatformFlowConfig(
            apiBaseUrl = "https://api.example.com/",
            apiKey = "secret",
            theme = theme,
        )
        assertSame(theme, cfg.theme)
    }

    // ── Validation ───────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun blank_apiBaseUrl_is_rejected() {
        PlatformFlowConfig(apiBaseUrl = "   ", apiKey = "k")
    }

    @Test(expected = IllegalArgumentException::class)
    fun empty_apiBaseUrl_is_rejected() {
        PlatformFlowConfig(apiBaseUrl = "", apiKey = "k")
    }

    @Test(expected = IllegalArgumentException::class)
    fun blank_apiKey_is_rejected() {
        PlatformFlowConfig(apiBaseUrl = "https://api.example.com/", apiKey = "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun zero_cacheTtlMinutes_is_rejected() {
        PlatformFlowConfig(
            apiBaseUrl = "https://api.example.com/",
            apiKey = "k",
            cacheTtlMinutes = 0L,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun negative_cacheTtlMinutes_is_rejected() {
        PlatformFlowConfig(
            apiBaseUrl = "https://api.example.com/",
            apiKey = "k",
            cacheTtlMinutes = -1L,
        )
    }

    // ── normalizedBaseUrl ─────────────────────────────────────────────

    @Test
    fun normalizedBaseUrl_appends_slash_when_missing() {
        val cfg = PlatformFlowConfig(
            apiBaseUrl = "https://api.example.com",
            apiKey = "k",
        )
        assertEquals("https://api.example.com/", cfg.normalizedBaseUrl)
    }

    @Test
    fun normalizedBaseUrl_passes_through_when_already_terminated() {
        val cfg = PlatformFlowConfig(
            apiBaseUrl = "https://api.example.com/v1/",
            apiKey = "k",
        )
        assertEquals("https://api.example.com/v1/", cfg.normalizedBaseUrl)
    }

    @Test
    fun normalizedBaseUrl_preserves_path_segments() {
        val cfg = PlatformFlowConfig(
            apiBaseUrl = "https://api.example.com/sdk/v2",
            apiKey = "k",
        )
        assertTrue(cfg.normalizedBaseUrl.endsWith("/"))
        assertTrue(cfg.normalizedBaseUrl.startsWith("https://api.example.com/sdk/v2"))
    }
}
