package com.navisense.model

/**
 * Navigation mode for the NaviSense app.
 *
 * - [SCANNER]: On-demand mode. Each ViT location result is a one-shot scan,
 *   and the location state (age) is actively tracked.
 * - [DASHCAM]: Continuous/live mode. Location is assumed always fresh
 *   (the continuous polling loop will be implemented later).
 */
enum class NavMode {
    SCANNER,
    DASHCAM
}
