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
    PHARMACY("Pharmacy"),
    NO_CATEGORY("No Category");

    companion object {
        /** All category keys for serialisation / filter matching. */
        val names: List<String> = entries.map { it.key }

        /** Parse a raw key string back to an enum entry (case-sensitive). */
        fun fromKey(key: String): AppLocationCategory =
            entries.firstOrNull { it.key == key } ?: MONUMENT

        /** Color hue for map markers by category. */
        fun markerHue(key: String): Float {
            return when (key) {
                MONUMENT.key -> 0f       // Red
                GROCERY.key -> 120f      // Green
                GAS_STATION.key -> 30f   // Orange
                RESTAURANT.key -> 180f   // Cyan
                PHARMACY.key -> 240f     // Blue
                NO_CATEGORY.key -> 0f    // Red (fallback)
                else -> 0f
            }
        }

        /** Chart color (int) for category. */
        fun chartColor(key: String): Int {
            return when (key) {
                MONUMENT.key -> 0xFFE53935.toInt()
                GROCERY.key -> 0xFF43A047.toInt()
                GAS_STATION.key -> 0xFFFB8C00.toInt()
                RESTAURANT.key -> 0xFF00ACC1.toInt()
                PHARMACY.key -> 0xFF1E88E5.toInt()
                NO_CATEGORY.key -> 0xFF9E9E9E.toInt()
                else -> 0xFF9E9E9E.toInt()
            }
        }
    }
}
