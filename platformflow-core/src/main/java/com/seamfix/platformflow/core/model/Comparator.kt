package com.seamfix.platformflow.core.model

/**
 * Edge-rule comparator. Wire JSON values use the `token` strings exactly as
 * the Admin UI emits them ("==", "!=", ">", ">=", "in", "notIn", "exists",
 * "notExists" …). The Kotlin enum names use canonical screaming-snake form
 * so they're idiomatic in code.
 *
 * Per SDK Architecture §3.4.
 */
enum class Comparator(val token: String) {
    EQ("=="),
    NEQ("!="),
    GT(">"),
    LT("<"),
    GTE(">="),
    LTE("<="),
    IN("in"),
    NOT_IN("notIn"),
    EXISTS("exists"),
    NOT_EXISTS("notExists");

    companion object {
        private val byToken: Map<String, Comparator> =
            entries.associateBy { it.token }

        /** Parse the wire token (`"=="`, `"in"`, …) into the enum. */
        fun fromJson(s: String): Comparator =
            byToken[s] ?: throw IllegalArgumentException("Unknown comparator: $s")
    }
}
