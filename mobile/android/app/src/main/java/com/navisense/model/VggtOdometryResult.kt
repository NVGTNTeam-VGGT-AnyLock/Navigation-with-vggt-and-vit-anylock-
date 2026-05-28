package com.navisense.model

/**
 * Result from the VGGT-1B visual odometry endpoint.
 *
 * [cameraCenterOffset] is the 3D metric translation vector [x, y, z]
 * representing the relative camera-centre movement from the first
 * to the last frame in the sequence.
 *
 * [bearingDegrees] is computed as `atan2(x, z)` converted to degrees,
 * representing the heading direction (0° = forward along z-axis).
 */
data class VggtOdometryResult(
    val cameraCenterOffset: CameraOffset,
    val bearingDegrees: Double
) {

    companion object {
        /**
         * Factory method that creates a [VggtOdometryResult] from a raw
         * [VggtOdometryResponse] by computing the bearing internally.
         *
         * @param response The raw API response from the VGGT-1B backend.
         * @return A fully-populated [VggtOdometryResult] with bearing computed.
         */
        fun fromResponse(response: com.navisense.core.VggtOdometryResponse): VggtOdometryResult {
            val offset = response.camera_center_offset
            return VggtOdometryResult(
                cameraCenterOffset = offset,
                bearingDegrees = offset.toBearingDegrees()
            )
        }
    }
}

/**
 * 3D metric offset of the camera centre returned by the VGGT-1B model.
 *
 * The VGGT-1B operates in a right-handed camera coordinate system:
 *   - **+Z** = forward (direction the camera is pointing)
 *   - **+X** = right
 *   - **+Y** = up
 *
 * The returned [cameraCenterOffset] = [x, y, z] represents the
 * **displacement** of the camera center from frame 1 to frame N.
 *
 * @property x Lateral displacement (positive = rightward movement).
 * @property y Vertical displacement (positive = upward movement).
 * @property z Forward displacement (positive = forward movement).
 */
data class CameraOffset(
    val x: Double,
    val y: Double,
    val z: Double
) {
    /**
     * Bearing (heading) in degrees, normalized to [0, 360).
     *
     * ## Mathematical Derivation
     *
     * The VGGT-1B model operates in a right-handed camera coordinate
     * system where:
     *   - **+Z** = forward (direction the camera is pointing)
     *   - **+X** = right (perpendicular to the viewing direction)
     *   - **+Y** = up (gravity-aligned)
     *
     * The returned [cameraCenterOffset] = [x, y, z] represents the
     * **displacement** of the camera center from frame 1 to frame N.
     *
     * To compute the **bearing** (heading on the ground plane, i.e.
     * the XZ projection of movement direction), we take:
     *
     * ```
     * θ = atan2(x, z)
     * ```
     *
     * Where:
     *   - `atan2` is the 2-argument arctangent (returns [-π, π])
     *   - `x` = lateral displacement (positive = rightward movement)
     *   - `z` = forward displacement (positive = forward movement)
     *
     * If the person/car moved forward-and-right, x > 0 and z > 0,
     * so θ is small positive (e.g. +15° = bearing 15° right of centre).
     *
     * If the person/car moved backward, z < 0 so θ is ~180°,
     * indicating a U-turn.
     *
     * @return bearing in degrees, normalized to [0, 360)
     */
    fun toBearingDegrees(): Double {
        val radians = kotlin.math.atan2(x, z)
        val degrees = Math.toDegrees(radians)
        return (degrees + 360.0) % 360.0
    }
}
