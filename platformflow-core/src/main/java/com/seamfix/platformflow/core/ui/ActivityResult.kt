package com.seamfix.platformflow.core.ui

import android.content.Intent

/**
 * The result of a [ComponentHost.startActivityForResult] call. Mirrors
 * the platform Activity-result shape so components can read the standard
 * `resultCode` (typically `Activity.RESULT_OK` / `RESULT_CANCELED`) and
 * unpack any returned data from the Intent's extras.
 *
 * This is a stable SDK-owned value class rather than a re-export of
 * `androidx.activity.result.ActivityResult` so the SDK API stays
 * decoupled from AndroidX version churn.
 */
data class ActivityResult(
    /** `Activity.RESULT_OK`, `RESULT_CANCELED`, or a custom code. */
    val resultCode: Int,

    /** The Intent the launched Activity returned, or `null` on cancel. */
    val data: Intent? = null,
)
