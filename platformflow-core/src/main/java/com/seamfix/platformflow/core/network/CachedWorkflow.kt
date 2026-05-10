package com.seamfix.platformflow.core.network

import com.seamfix.platformflow.core.model.WorkflowDefinition

/**
 * One entry in the [WorkflowCache]. Per SDK Architecture §14.2.
 *
 * @property workflow The cached definition.
 * @property fetchedAt Epoch-ms timestamp captured at insertion. The
 *  cache compares this against the configured TTL to classify entries
 *  as fresh or stale.
 */
data class CachedWorkflow(
    val workflow: WorkflowDefinition,
    val fetchedAt: Long,
)
