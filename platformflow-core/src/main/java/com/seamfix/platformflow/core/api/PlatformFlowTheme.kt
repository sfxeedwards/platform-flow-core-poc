package com.seamfix.platformflow.core.api

import androidx.annotation.DrawableRes

/**
 * Tenant-configurable visual theme applied across every workflow step.
 * Per SDK Architecture §18.1.
 *
 * Set on `PlatformFlowConfig` at SDK init time and threaded to every
 * [com.seamfix.platformflow.core.component.FlowComponent] via
 * [com.seamfix.platformflow.core.ui.ComponentHost.theme]. Built-in
 * components honour these values directly; custom components are
 * encouraged to read the theme off the host so the verification flow
 * looks consistent end-to-end.
 *
 * Defaults match the §18.1 reference (Material-purple primary, teal
 * secondary, white background, dark-red error tint, sans-serif font,
 * rounded buttons, no logo).
 *
 * @property primaryColor Brand-leading colour. Used for primary buttons,
 *  selected states, progress indicators.
 * @property secondaryColor Accent colour. Used for FAB-style secondary
 *  actions and highlights.
 * @property backgroundColor Page / sheet background.
 * @property errorColor Error states — red border on failed input,
 *  destructive button colour, etc.
 * @property fontFamily Logical font family name passed to the Android
 *  text rendering layer (e.g. `"sans-serif"`, `"serif"`,
 *  `"monospace"`, or a registered custom font name).
 * @property buttonStyle Corner-radius hint for primary buttons.
 * @property logoDrawable Optional `R.drawable.*` resource id rendered
 *  in component headers. `null` hides the logo.
 */
data class PlatformFlowTheme(
    val primaryColor: Color = DEFAULT_PRIMARY,
    val secondaryColor: Color = DEFAULT_SECONDARY,
    val backgroundColor: Color = Color.White,
    val errorColor: Color = DEFAULT_ERROR,
    val fontFamily: String = DEFAULT_FONT_FAMILY,
    val buttonStyle: ButtonStyle = ButtonStyle.ROUNDED,
    @DrawableRes val logoDrawable: Int? = null,
) {
    companion object {
        // Material-style defaults from §18.1.
        val DEFAULT_PRIMARY: Color = Color(0xFF6200EE)
        val DEFAULT_SECONDARY: Color = Color(0xFF03DAC5)
        val DEFAULT_ERROR: Color = Color(0xFFB00020)
        const val DEFAULT_FONT_FAMILY: String = "sans-serif"
    }
}
