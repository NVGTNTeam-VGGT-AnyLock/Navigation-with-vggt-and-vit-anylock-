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

// ── Fusion endpoint domain models ─────────────────────────────────────

/**
 * A single point in the 3D trajectory returned by VGGT-1B.
 *
 * Each point represents the camera-centre displacement **relative to the
 * first frame** in the image sequence.
 *
 * @property dx Lateral displacement (positive = rightward).
 * @property dy Vertical displacement (positive = upward).
 * @property dz Forward displacement (positive = forward).
 */
data class TrajectoryPoint(
    val dx: Double,
    val dy: Double,
    val dz: Double
)

/**
 * Normalised 2D heading vector on the ground plane.
 *
 * Derived from the last frame's forward-facing direction in the
 * VGGT-1B camera coordinate system (XZ plane), mapped to (x, y).
 *
 * @property x Lateral component (positive = rightward).
 * @property y Forward/depth component (positive = forward).
 */
data class HeadingVector(
    val x: Double,
    val y: Double
) {
    /**
     * Computes the bearing in degrees from the heading vector.
     *
     * Uses [atan2] where:
     *   - `x` = lateral component
     *   - `y` = forward component (mapped from camera-space Z)
     *
     * @return bearing in degrees, normalized to [0, 360).
     */
    fun toBearingDegrees(): Double {
        val radians = kotlin.math.atan2(x, y)
        val degrees = Math.toDegrees(radians)
        return (degrees + 360.0) % 360.0
    }
}

/**
 * Domain-level result from the fused navigation endpoint.
 *
 * Combines the absolute position from ViT with the trajectory
 * and heading from VGGT-1B into a single navigation data object.
 *
 * @param latitude       WGS‑84 latitude from ViT visual place recognition.
 * @param longitude      WGS‑84 longitude from ViT visual place recognition.
 * @param trajectory     Per-frame 3D displacements from VGGT-1B.
 * @param headingVector  Normalised 2D forward direction from VGGT-1B.
 */
data class NavigateFusionResult(
    val latitude: Double,
    val longitude: Double,
    val trajectory: List<TrajectoryPoint>,
    val headingVector: HeadingVector
) {
    companion object {
        /**
         * Factory that converts a raw [NavigateFusionResponse] into a
         * domain-level [NavigateFusionResult].
         */
        fun fromResponse(
            response: com.navisense.core.NavigateFusionResponse
        ): NavigateFusionResult {
            return NavigateFusionResult(
                latitude = response.current_location.lat,
                longitude = response.current_location.lng,
                trajectory = response.trajectory,
                headingVector = response.heading_vector
            )
        }
    }
}
