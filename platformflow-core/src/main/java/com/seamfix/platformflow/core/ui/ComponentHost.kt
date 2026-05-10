package com.seamfix.platformflow.core.ui

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.seamfix.platformflow.core.api.PlatformFlowTheme

/**
 * Bridge from a [com.seamfix.platformflow.core.component.FlowComponent] to
 * the host Android environment. Per SDK Architecture §7.3.
 *
 * Decouples components from the specific Activity / Fragment they're
 * running inside. The SDK's public-API layer (Task 27) constructs a
 * concrete host bound to the host app's launching Activity; components
 * see only this interface.
 *
 * Three responsibilities:
 *
 *  - **UI hosting** — [showFragment] swaps a SDK-provided Fragment into
 *    the workflow container and suspends until the user finishes.
 *  - **External activities** — [startActivityForResult] launches an
 *    Activity (e.g., a vendor SDK) and suspends until it returns.
 *  - **Loading affordances** — [withLoading] wraps a background block in
 *    a progress indicator so components don't have to reach for the
 *    Activity directly.
 *
 * Plus [context] for resource access (themes, strings, Asset bundles
 * etc.). Implementations typically return the host Activity or its
 * `applicationContext` depending on what the calling code needs.
 */
interface ComponentHost {

    /**
     * The Android Context for resource lookups (strings, drawables,
     * theming) and for components that need to inspect package state.
     * Don't store this beyond the duration of `execute(...)` — the
     * underlying Activity may finish.
     */
    val context: Context

    /**
     * The active visual theme. Per SDK Architecture §18.2 the host
     * threads this from `PlatformFlowConfig` to every component so they
     * can render consistently. Has a default-value getter for backward
     * compatibility — implementations that don't care (test fixtures,
     * legacy hosts) keep compiling and consumers see the
     * [PlatformFlowTheme] defaults.
     */
    val theme: PlatformFlowTheme get() = PlatformFlowTheme()

    /**
     * Show a Fragment in the workflow container and suspend until it
     * completes. The Fragment signals completion by setting a fragment
     * result (typically via the AndroidX FragmentResultListener API or a
     * shared ViewModel) which the host translates into a [FragmentResult].
     *
     * Cancellation (back press, swipe-dismiss) surfaces as
     * `FragmentResult(cancelled = true)`. The calling component decides
     * whether to retry, fall through, or report a
     * [com.seamfix.platformflow.core.component.ComponentResult.Failure].
     */
    suspend fun showFragment(fragment: Fragment): FragmentResult

    /**
     * Launch an Activity and suspend until it returns its result.
     * Wraps the platform Activity-result API so components don't have to
     * register launchers themselves.
     */
    suspend fun startActivityForResult(intent: Intent): ActivityResult

    /**
     * Show a loading indicator for the duration of [block], dismissing
     * it whether the block returns normally or throws. [message] is the
     * label the host shows next to the spinner.
     *
     * Components use this for short, opaque background work (network
     * calls, on-device matching) where the user shouldn't tap anything.
     * Long-running interactive work belongs inside a Fragment.
     */
    suspend fun <T> withLoading(message: String, block: suspend () -> T): T
}
