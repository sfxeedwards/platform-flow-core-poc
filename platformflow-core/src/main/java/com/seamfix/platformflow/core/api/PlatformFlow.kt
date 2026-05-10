package com.seamfix.platformflow.core.api

import android.content.Context
import android.content.Intent
import com.seamfix.platformflow.core.component.FlowComponent
import com.seamfix.platformflow.core.network.WorkflowCacheConfig
import com.seamfix.platformflow.core.network.WorkflowClients
import com.seamfix.platformflow.core.ui.ComponentHost
import com.seamfix.platformflow.core.ui.PlatformFlowActivity
import java.util.concurrent.TimeUnit

/**
 * Entry point for the SDK. Per SDK Architecture §11.
 *
 * Singleton façade over [PlatformFlowClient]. Production hosts call:
 *
 * ```
 * PlatformFlow.initialize(PlatformFlowConfig(...))            // once
 * PlatformFlow.registerComponent(MyCustomComponent())          // optional
 * PlatformFlow.start(context, workflowId, input, callbacks)    // per session
 * ```
 *
 * Tests typically construct a [PlatformFlowClient] directly with fakes
 * — the singleton's state is global and prone to leaking between
 * tests. See [PlatformFlowClient] for the full contract.
 *
 * **Two `start` shapes.**
 *
 *  - [start] (Context overload) — the Activity-bound default. Stashes
 *    the workflow request on the singleton, fires up
 *    [PlatformFlowActivity], and returns immediately. The Activity
 *    drives the engine and dispatches callbacks. This is what §11.2
 *    documents.
 *  - [startWithHost] (suspend overload) — the host supplies its own
 *    [ComponentHost] and awaits the engine. Useful for embedded /
 *    headless hosts and for tests.
 *
 * **Why singleton stash, not Intent extras.** Input bags routinely
 * contain Base64-encoded images (selfies, ID photos) which blow past
 * Android's 1 MB Binder transaction limit. Stashing on the singleton
 * dodges Binder entirely — the Activity is in the same process, so
 * the in-memory reference is safe. Per Task 30 §11.2 deviation note.
 *
 * **Reset.** Call [reset] in test teardown to clear the singleton's
 * state. Production code never resets — the SDK is initialised once
 * per process.
 */
object PlatformFlow {

    @Volatile
    private var client: PlatformFlowClient? = null

    /** State stashed by [start] for [PlatformFlowActivity] to consume. */
    @Volatile
    private var pendingStart: PendingStart? = null

    /** True once [initialize] has run successfully. */
    val isInitialized: Boolean get() = client != null

    /**
     * Wire up the SDK with the supplied [config]. Idempotent — calling
     * this a second time discards the previous client and the
     * registered custom components. Typically invoked from
     * `Application.onCreate()`.
     */
    fun initialize(config: PlatformFlowConfig) {
        client = PlatformFlowClient(
            config = config,
            workflowClient = WorkflowClients.retrofit(
                baseUrl = config.normalizedBaseUrl,
                cacheConfig = WorkflowCacheConfig(
                    ttlMs = TimeUnit.MINUTES.toMillis(config.cacheTtlMinutes),
                ),
            ),
        )
    }

    /**
     * Register a host-supplied [FlowComponent]. Per §11.4 the
     * registered component overrides any built-in with the same
     * [FlowComponent.type] (last-write-wins).
     */
    fun registerComponent(component: FlowComponent) {
        requireInitialized().registerComponent(component)
    }

    /**
     * Start one workflow session, hosted by the SDK's
     * [PlatformFlowActivity]. Per SDK Architecture §11.2.
     *
     * Stashes the request on the singleton (no Intent extras, see
     * class KDoc) and fires up the Activity. Returns immediately —
     * the engine runs on the Activity's lifecycle scope. Callbacks
     * are delivered on the main thread per §12.1.
     *
     * Throws [IllegalStateException] if [initialize] hasn't been
     * called or if a previous start is still pending.
     */
    fun start(
        context: Context,
        workflowId: String,
        input: Map<String, Any>,
        callbacks: PlatformFlowCallbacks,
    ) {
        requireInitialized()
        check(pendingStart == null) {
            "A workflow session is already starting. Wait for the active " +
                "PlatformFlowActivity to finish before launching another."
        }
        pendingStart = PendingStart(workflowId, input, callbacks)
        val intent = Intent(context, PlatformFlowActivity::class.java)
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Start one workflow session against a host-supplied
     * [ComponentHost]. Suspends until the engine reaches a terminal
     * state. See [PlatformFlowClient.start] for the full contract.
     *
     * Use [start] (Context overload) instead unless you need to host
     * the workflow yourself (e.g. embedded UI, instrumentation tests).
     *
     * Throws [IllegalStateException] if [initialize] hasn't been called.
     */
    suspend fun startWithHost(
        host: ComponentHost,
        workflowId: String,
        input: Map<String, Any>,
        callbacks: PlatformFlowCallbacks,
    ) {
        requireInitialized().start(host, workflowId, input, callbacks)
    }

    /**
     * Ship a [SessionResult] to the backend. See
     * [PlatformFlowClient.reportResult] for the contract.
     */
    suspend fun reportResult(result: SessionResult) {
        requireInitialized().reportResult(result)
    }

    /**
     * Drop the singleton's state. Tests should call this in teardown
     * to keep state from leaking between test methods.
     */
    fun reset() {
        client = null
        pendingStart = null
    }

    /** Internal hook for [PlatformFlowActivity]. Returns + clears the pending request. */
    internal fun consumePendingStart(): PendingStart? {
        val v = pendingStart
        pendingStart = null
        return v
    }

    /** Internal hook for [PlatformFlowActivity]. Returns the active client or throws. */
    internal fun requireClient(): PlatformFlowClient = requireInitialized()

    private fun requireInitialized(): PlatformFlowClient =
        client ?: error("PlatformFlow.initialize(...) must be called first")

    /** Snapshot of one [start] request. Internal — produced + consumed inside the SDK. */
    internal data class PendingStart(
        val workflowId: String,
        val input: Map<String, Any>,
        val callbacks: PlatformFlowCallbacks,
    )
}
