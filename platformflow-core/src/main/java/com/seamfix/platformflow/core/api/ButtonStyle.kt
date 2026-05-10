package com.seamfix.platformflow.core.api

/**
 * Visual style hint for buttons rendered by built-in components. Per
 * SDK Architecture §18.1.
 *
 * The SDK doesn't enforce these styles at render time — built-in
 * components and theme-aware custom components consult [PlatformFlowTheme.buttonStyle]
 * and apply matching corner radii / paddings / strokes themselves.
 */
enum class ButtonStyle {
    /** Default: rounded corners. */
    ROUNDED,

    /** Sharp 90° corners. */
    SQUARE,
}
