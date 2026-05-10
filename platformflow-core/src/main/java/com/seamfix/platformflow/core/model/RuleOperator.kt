package com.seamfix.platformflow.core.model

/**
 * Boolean combinator for an [EdgeRule]'s conditions.
 * Wire JSON values match the enum names exactly: `"AND"`, `"OR"`.
 *
 * Per SDK Architecture §3.4.
 */
enum class RuleOperator { AND, OR }
