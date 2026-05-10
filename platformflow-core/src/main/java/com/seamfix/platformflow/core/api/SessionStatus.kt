package com.seamfix.platformflow.core.api

/**
 * Terminal status of a workflow session. Per SDK Architecture §11.3.
 *
 * Only [COMPLETED] reaches the host via `onComplete`; the other two
 * arms are surfaced through `onError` / `onCancelled` respectively.
 * The enum is included on every [SessionResult] so audit pipelines can
 * branch on the canonical state regardless of which callback fired.
 */
enum class SessionStatus {
    /** Every node ran to a Success. */
    COMPLETED,

    /**
     * Validation failed, a component returned Failure, or an
     * unexpected exception escaped the engine.
     */
    FAILED,

    /** User backed out / closed the SDK before completion. */
    CANCELLED,
}
