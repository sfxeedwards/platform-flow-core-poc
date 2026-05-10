package com.seamfix.platformflow.core.ui

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.seamfix.platformflow.core.api.PlatformFlowTheme
import com.seamfix.platformflow.core.ui.FragmentResultBundles.KEY_COMPONENT_RESULT
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Concrete [ComponentHost] bound to [PlatformFlowActivity]. Per SDK
 * Architecture §7.3 and Task 30.
 *
 * **`showFragment`** swaps the supplied Fragment into the host's
 * `pf_flow_container` and suspends on a `setFragmentResultListener`
 * keyed by [KEY_COMPONENT_RESULT]. Components emit their result via
 * `parentFragmentManager.setFragmentResult(KEY_COMPONENT_RESULT, bundle)`
 * — see [FragmentResultBundles] for the Bundle convention. The
 * listener is registered with `activity` as its lifecycle owner so
 * AndroidX cleans it up on Activity destruction; we additionally
 * unregister on coroutine cancellation via `invokeOnCancellation` to
 * avoid a stale listener firing into a stale continuation.
 *
 * **`startActivityForResult`** suspends on an
 * [ActivityResultLauncher] registered up-front in the Activity's
 * `onCreate` (the AndroidX contract requires registration before the
 * Activity is `STARTED`). Only one launch can be in-flight at a time;
 * a second concurrent call throws.
 *
 * **`withLoading`** toggles the host's loading overlay around the
 * supplied block. The overlay covers the whole screen and intercepts
 * touches so the user can't tap through to the underlying Fragment.
 *
 * **`theme`** is supplied by [PlatformFlowActivity] from the active
 * [com.seamfix.platformflow.core.api.PlatformFlowConfig].
 *
 * Construction is `internal` — the SDK creates one of these per
 * Activity instance; host code never sees it directly.
 */
internal class ComponentHostImpl(
    private val activity: PlatformFlowActivity,
    private val activityResultLauncher: ActivityResultLauncher<Intent>,
    private val themeSnapshot: PlatformFlowTheme,
) : ComponentHost {

    override val context: Context get() = activity

    override val theme: PlatformFlowTheme get() = themeSnapshot

    /**
     * Set by [startActivityForResult] before launching. The
     * [PlatformFlowActivity]-registered launcher resumes this on
     * result. Single-slot — concurrent launches throw.
     */
    @Volatile
    private var pendingActivityContinuation: CancellableContinuation<ActivityResult>? = null

    override suspend fun showFragment(fragment: Fragment): FragmentResult {
        return suspendCancellableCoroutine { cont ->
            // Hide the between-step transition overlay so the fragment is visible.
            activity.hideStepOverlay()
            val fm = activity.supportFragmentManager
            // Register the listener BEFORE committing the fragment so
            // we don't miss a fast-finishing fragment that emits its
            // result during onStart.
            fm.setFragmentResultListener(
                KEY_COMPONENT_RESULT,
                activity,
            ) { _, bundle ->
                if (cont.isActive) {
                    cont.resume(FragmentResultBundles.bundleToFragmentResult(bundle))
                }
                // Clear the listener so it doesn't fire again for the next fragment.
                fm.clearFragmentResultListener(KEY_COMPONENT_RESULT)
            }

            cont.invokeOnCancellation {
                // Best effort cleanup if the engine's coroutine is cancelled
                // while a fragment is still on screen.
                try {
                    fm.clearFragmentResultListener(KEY_COMPONENT_RESULT)
                } catch (_: IllegalStateException) {
                    // FragmentManager is already destroyed — nothing to clean up.
                }
            }

            fm.beginTransaction()
                .replace(activity.flowContainerId, fragment)
                .commitAllowingStateLoss()
        }
    }

    override suspend fun startActivityForResult(intent: Intent): ActivityResult {
        return suspendCancellableCoroutine { cont ->
            val existing = pendingActivityContinuation
            if (existing != null) {
                cont.resumeWith(
                    Result.failure(
                        IllegalStateException(
                            "startActivityForResult is already in flight; only one concurrent launch is supported.",
                        ),
                    ),
                )
                return@suspendCancellableCoroutine
            }
            pendingActivityContinuation = cont
            cont.invokeOnCancellation { pendingActivityContinuation = null }

            try {
                activityResultLauncher.launch(intent)
            } catch (t: Throwable) {
                pendingActivityContinuation = null
                cont.resumeWith(Result.failure(t))
            }
        }
    }

    /**
     * Called by [PlatformFlowActivity] when its registered
     * [ActivityResultLauncher] callback fires. Hands the result to
     * whatever coroutine is suspended on [startActivityForResult].
     */
    internal fun deliverActivityResult(resultCode: Int, data: Intent?) {
        val cont = pendingActivityContinuation
        pendingActivityContinuation = null
        if (cont != null && cont.isActive) {
            cont.resume(ActivityResult(resultCode = resultCode, data = data))
        }
    }

    override suspend fun <T> withLoading(message: String, block: suspend () -> T): T {
        activity.showLoadingOverlay(message)
        return try {
            block()
        } finally {
            activity.hideLoadingOverlay()
        }
    }
}
