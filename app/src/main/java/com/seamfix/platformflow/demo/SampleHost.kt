package com.seamfix.platformflow.demo

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.seamfix.platformflow.core.ui.ActivityResult
import com.seamfix.platformflow.core.ui.ComponentHost
import com.seamfix.platformflow.core.ui.FragmentResult

/**
 * Minimal [ComponentHost] for the sample app.
 *
 * The built-in component stubs don't actually render Fragments or launch
 * Activities — they return placeholder [ComponentResult.Success] values
 * synchronously — so [showFragment] and [startActivityForResult] are
 * intentionally unsupported here. If a real component is later
 * registered that needs UI, this host needs to be replaced with the
 * Activity-bound default that ships in the follow-up task.
 *
 * [withLoading] just runs the block — the demo doesn't render a
 * spinner.
 */
internal class SampleHost(
    private val applicationContext: Context,
) : ComponentHost {

    override val context: Context get() = applicationContext

    override suspend fun showFragment(fragment: Fragment): FragmentResult =
        error(
            "SampleHost.showFragment is unsupported — the demo only exercises " +
                "stub components that don't render UI.",
        )

    override suspend fun startActivityForResult(intent: Intent): ActivityResult =
        error(
            "SampleHost.startActivityForResult is unsupported — the demo only " +
                "exercises stub components that don't launch activities.",
        )

    override suspend fun <T> withLoading(message: String, block: suspend () -> T): T =
        block()
}
