package com.seamfix.platformflow.core.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.seamfix.platformflow.core.R
import com.seamfix.platformflow.core.api.PlatformFlow
import com.seamfix.platformflow.core.api.PlatformFlowCallbacks
import com.seamfix.platformflow.core.api.PlatformFlowError
import com.seamfix.platformflow.core.api.SessionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The SDK's UI shell. Per Task 30 + SDK Architecture §11.2 / §13.4.
 *
 * Driven entirely off the [PlatformFlow] singleton's pending-start
 * stash — no Intent extras (input bags routinely contain Base64
 * images that exceed Android's 1 MB Binder limit). Layout layers a
 * Fragment host with three overlays: step transition, loading
 * spinner, and error/retry.
 *
 * **Lifecycle.**
 *  1. `onCreate` reads the pending request from the singleton, builds
 *     the [ComponentHostImpl], and launches the engine on
 *     `lifecycleScope`.
 *  2. The engine runs through the workflow. Components either render
 *     UI via the host or compute synchronously.
 *  3. On terminal status the wrapped callbacks fire user-supplied
 *     callbacks and (for success / cancellation) `finish()` the
 *     Activity. Errors land on the error overlay; the user's
 *     `onError` is deferred until they tap Cancel so the SDK honours
 *     §11.2's "exactly one terminal callback per session" contract.
 *     Retry re-runs the workflow in-place.
 *  4. Back press cancels the lifecycleScope, which propagates a
 *     `CancellationException` through the engine; the client maps
 *     that to `onCancelled()` and the Activity finishes.
 *
 * Internal — host apps go through [PlatformFlow.start].
 */
internal class PlatformFlowActivity : AppCompatActivity() {

    private lateinit var pending: PlatformFlow.PendingStart

    private lateinit var rootView: View
    private lateinit var stepOverlay: View
    private lateinit var stepText: TextView
    private lateinit var loadingOverlay: View
    private lateinit var loadingText: TextView
    private lateinit var errorOverlay: View
    private lateinit var errorTitle: TextView
    private lateinit var errorMessage: TextView
    private lateinit var errorRetry: Button
    private lateinit var errorCancel: Button

    private lateinit var activityLauncher: ActivityResultLauncher<Intent>
    private lateinit var host: ComponentHostImpl

    private var engineJob: Job? = null

    /**
     * The id of the FrameLayout into which components mount Fragments.
     * Exposed so [ComponentHostImpl.showFragment] can target it.
     */
    val flowContainerId: Int get() = R.id.pf_flow_container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Capture pending state BEFORE setContentView so a missing-stash
        // case bails fast without inflating layouts.
        val captured = PlatformFlow.consumePendingStart()
        if (captured == null) {
            Log.e(
                TAG,
                "PlatformFlowActivity launched with no pending start. " +
                    "This usually means the process was killed and recreated. Finishing.",
            )
            finish()
            return
        }
        pending = captured

        // Edge-to-edge — apply system insets to the root so overlays don't
        // hide under the status / nav bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.pf_activity_main)
        bindViews()

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            v.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        // Register Activity-result launcher up-front (AndroidX requires
        // this before STARTED state).
        activityLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            host.deliverActivityResult(result.resultCode, result.data)
        }

        host = ComponentHostImpl(
            activity = this,
            activityResultLauncher = activityLauncher,
            themeSnapshot = PlatformFlow.requireClient().config.theme,
        )

        errorRetry.setOnClickListener { onRetryClicked() }
        errorCancel.setOnClickListener { onCancelClicked() }

        runEngine(pending)
    }

    private fun bindViews() {
        rootView = findViewById(R.id.pf_root)
        stepOverlay = findViewById(R.id.pf_step_overlay)
        stepText = findViewById(R.id.pf_step_text)
        loadingOverlay = findViewById(R.id.pf_loading_overlay)
        loadingText = findViewById(R.id.pf_loading_text)
        errorOverlay = findViewById(R.id.pf_error_overlay)
        errorTitle = findViewById(R.id.pf_error_title)
        errorMessage = findViewById(R.id.pf_error_message)
        errorRetry = findViewById(R.id.pf_error_retry)
        errorCancel = findViewById(R.id.pf_error_cancel)
    }

    private fun runEngine(p: PlatformFlow.PendingStart) {
        // Show "Loading…" until the first component takes the screen.
        showStepOverlay(getString(R.string.pf_loading))
        hideErrorOverlay()

        val client = PlatformFlow.requireClient()
        val wrapped = WrappingCallbacks(p.callbacks)

        engineJob = lifecycleScope.launch {
            try {
                client.start(
                    host = host,
                    workflowId = p.workflowId,
                    input = p.input,
                    callbacks = wrapped,
                )
            } catch (cancel: CancellationException) {
                // The client already fired onCancelled before rethrowing;
                // honour structured concurrency by letting it unwind.
                throw cancel
            }
        }
    }

    /** Re-run the workflow with the same parameters. Resets overlays. */
    private fun onRetryClicked() {
        engineJob?.cancel()
        engineJob = null
        runEngine(pending)
    }

    /** User dismissed the error screen — surface the stored error and finish. */
    private fun onCancelClicked() {
        val err = pendingError
        pendingError = null
        if (err != null) {
            // Deliver the deferred onError now that the user has acknowledged it.
            try {
                pending.callbacks.onError(err)
            } catch (t: Throwable) {
                Log.w(TAG, "User onError callback threw", t)
            }
        }
        finish()
    }

    // ── Overlay helpers (called from ComponentHostImpl + WrappingCallbacks) ──

    fun showStepOverlay(text: String) {
        stepText.text = text
        stepOverlay.visibility = View.VISIBLE
    }

    fun hideStepOverlay() {
        stepOverlay.visibility = View.GONE
    }

    fun showLoadingOverlay(message: String) {
        loadingText.text = message
        loadingOverlay.visibility = View.VISIBLE
    }

    fun hideLoadingOverlay() {
        loadingOverlay.visibility = View.GONE
    }

    private fun showErrorOverlay(title: String, message: String) {
        errorTitle.text = title
        errorMessage.text = message
        errorOverlay.visibility = View.VISIBLE
        // Hide step / loading so only the error screen is visible.
        hideStepOverlay()
        hideLoadingOverlay()
    }

    private fun hideErrorOverlay() {
        errorOverlay.visibility = View.GONE
    }

    // ── Wrapped callbacks ─────────────────────────────────────────────

    private var pendingError: PlatformFlowError? = null

    /**
     * Delegates to the user's callbacks while driving overlays + the
     * deferred-error pattern. Per §11.2 we guarantee exactly one
     * terminal callback per session, even though the engine can
     * surface multiple errors if the user keeps tapping Retry.
     */
    private inner class WrappingCallbacks(
        private val user: PlatformFlowCallbacks,
    ) : PlatformFlowCallbacks {

        override fun onStepComplete(nodeId: String, stepIndex: Int, totalSteps: Int) {
            // Surface the transition between components — gets hidden as
            // soon as the next component calls host.showFragment.
            showStepOverlay(getString(R.string.pf_step_format, stepIndex + 1, totalSteps))
            try {
                user.onStepComplete(nodeId, stepIndex, totalSteps)
            } catch (t: Throwable) {
                Log.w(TAG, "User onStepComplete callback threw", t)
            }
        }

        override fun onComplete(result: SessionResult) {
            try {
                user.onComplete(result)
            } catch (t: Throwable) {
                Log.w(TAG, "User onComplete callback threw", t)
            }
            finish()
        }

        override fun onError(error: PlatformFlowError) {
            pendingError = error
            showErrorOverlay(
                title = getString(R.string.pf_error_title_default),
                message = error.userMessage(),
            )
            // User's onError is deferred until they tap Cancel.
        }

        override fun onCancelled() {
            try {
                user.onCancelled()
            } catch (t: Throwable) {
                Log.w(TAG, "User onCancelled callback threw", t)
            }
            finish()
        }
    }

    companion object {
        private const val TAG = "PlatformFlowActivity"
    }
}

/** Pull a human-readable string off the sealed [PlatformFlowError]. */
private fun PlatformFlowError.userMessage(): String = when (this) {
    is PlatformFlowError.NetworkError -> message
    is PlatformFlowError.ValidationError -> message
    is PlatformFlowError.ComponentError -> reason
    is PlatformFlowError.InternalError -> message
}
