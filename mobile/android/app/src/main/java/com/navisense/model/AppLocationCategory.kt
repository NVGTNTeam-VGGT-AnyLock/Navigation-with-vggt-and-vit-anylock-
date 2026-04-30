package com.navisense.model

/**
 * Predefined categories for [AppLocation].
 *
 * Each category has a translatable string key so the UI can display
 * the correct localised label via `context.getString(category.labelRes)`.
 */
enum class AppLocationCategory(val key: String) {
    MONUMENT("Monument"),
    GROCERY("Grocery"),
    GAS_STATION("Gas Station"),
    RESTAURANT("Restaurant"),
    PHARMACY("Pharmacy");

    companion object {
        /** All category keys for serialisation / filter matching. */
        val names: List<String> = entries.map { it.key }

        /** Parse a raw key string back to an enum entry (case-sensitive). */
        fun fromKey(key: String): AppLocationCategory =
            entries.firstOrNull { it.key == key } ?: MONUMENT
    }
}
