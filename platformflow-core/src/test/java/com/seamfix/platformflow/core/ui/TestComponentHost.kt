package com.seamfix.platformflow.core.ui

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment

/**
 * Test-only [ComponentHost] that satisfies the interface without binding
 * to a real Android Activity.
 *
 *  - [context] throws by default. Tests that need a real Context should
 *    pull in Robolectric and pass it via [contextProvider].
 *  - [showFragment] / [startActivityForResult] throw by default — tests
 *    asserting these paths inject the lambdas they want.
 *  - [withLoading] simply runs the block. Loading UI is a host concern
 *    and irrelevant to logic-level tests.
 *
 * Keeps the unit-test source set free of Android lifecycle dependencies.
 */
internal class TestComponentHost(
    private val contextProvider: () -> Context = {
        error("TestComponentHost.context not configured; use Robolectric or pass a Context provider")
    },
    private val onShowFragment: suspend (Fragment) -> FragmentResult = {
        error("TestComponentHost.showFragment not configured")
    },
    private val onStartActivityForResult: suspend (Intent) -> ActivityResult = {
        error("TestComponentHost.startActivityForResult not configured")
    },
) : ComponentHost {

    override val context: Context get() = contextProvider()

    override suspend fun showFragment(fragment: Fragment): FragmentResult =
        onShowFragment(fragment)

    override suspend fun startActivityForResult(intent: Intent): ActivityResult =
        onStartActivityForResult(intent)

    override suspend fun <T> withLoading(message: String, block: suspend () -> T): T =
        block()
}
