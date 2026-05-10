package com.seamfix.platformflow.core.model

/**
 * Boolean condition tree on an edge: an [operator] (AND/OR) and a list of
 * [RuleItem]s. A condition list may mix leaf conditions and nested rule
 * groups.
 *
 * Per SDK Architecture §3.4.
 */
data class EdgeRule(
    val operator: RuleOperator,
    val conditions: List<RuleItem>,
)
