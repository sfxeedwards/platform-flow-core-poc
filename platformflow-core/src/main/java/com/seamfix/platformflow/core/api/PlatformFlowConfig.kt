package com.seamfix.platformflow.core.api

/**
 * SDK-wide configuration passed once via [PlatformFlow.initialize]. Per
 * SDK Architecture §11.1.
 *
 * @property apiBaseUrl Server root URL. The SDK normalises this to
 *  ensure it ends in `/` (Retrofit's hard requirement).
 * @property apiKey Tenant API key, surfaced on every backend request.
 *  Treat as a secret; never log.
 * @property cacheTtlMinutes How long fetched workflow definitions stay
 *  fresh in the in-memory cache before stale-while-revalidate kicks in.
 *  Default 24 hours per §14.2.
 * @property theme Visual theme threaded to every component via
 *  [com.seamfix.platformflow.core.ui.ComponentHost.theme]. Defaults to
 *  the §18.1 reference theme.
 */
data class PlatformFlowConfig(
    val apiBaseUrl: String,
    val apiKey: String,
    val cacheTtlMinutes: Long = DEFAULT_CACHE_TTL_MINUTES,
    val theme: PlatformFlowTheme = PlatformFlowTheme(),
) {
    init {
        require(apiBaseUrl.isNotBlank()) { "apiBaseUrl must not be blank" }
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        require(cacheTtlMinutes > 0) {
            "cacheTtlMinutes must be positive (got $cacheTtlMinutes)"
        }
    }

    /** [apiBaseUrl] guaranteed to end in `/` so Retrofit's `Builder.baseUrl()` accepts it. */
    val normalizedBaseUrl: String =
        if (apiBaseUrl.endsWith("/")) apiBaseUrl else "$apiBaseUrl/"

    companion object {
        /** §11.1 default — 24 hours expressed in minutes. */
        const val DEFAULT_CACHE_TTL_MINUTES: Long = 60L * 24L
    }
}
