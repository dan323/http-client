package com.dan323.httpclient.desktop

import com.dan323.httpclient.core.port.out.WindowSettings

/** A rectangle in pixels, used for screen bounds. */
data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

/** Window size and optional position to start with; [x]/[y] null means "let the platform place it". */
data class Placement(val width: Int, val height: Int, val x: Int?, val y: Int?, val maximized: Boolean)

/** Turns remembered geometry into something safe to show: sane sizes, and a position only if the window is reachable on a current screen. */
object WindowGeometry {
    const val DEFAULT_WIDTH = 1280
    const val DEFAULT_HEIGHT = 800
    const val MIN_WIDTH = 600
    const val MIN_HEIGHT = 400
    private const val MAX = 20000
    private const val MIN_VISIBLE_WIDTH = 120
    private const val MIN_VISIBLE_HEIGHT = 60

    fun restore(saved: WindowSettings?, screens: List<Bounds>): Placement {
        if (saved == null) return Placement(DEFAULT_WIDTH, DEFAULT_HEIGHT, null, null, false)
        val maxW = screens.maxOfOrNull { it.width }?.coerceAtLeast(MIN_WIDTH) ?: MAX
        val maxH = screens.maxOfOrNull { it.height }?.coerceAtLeast(MIN_HEIGHT) ?: MAX
        val w = if (saved.width <= 0) DEFAULT_WIDTH.coerceAtMost(maxW) else saved.width.coerceIn(MIN_WIDTH, maxW)
        val h = if (saved.height <= 0) DEFAULT_HEIGHT.coerceAtMost(maxH) else saved.height.coerceIn(MIN_HEIGHT, maxH)
        val x = saved.x
        val y = saved.y
        val visible = x != null && y != null && screens.any { s ->
            val overlapW = minOf(x + w, s.x + s.width) - maxOf(x, s.x)
            val overlapH = minOf(y + h, s.y + s.height) - maxOf(y, s.y)
            overlapW >= MIN_VISIBLE_WIDTH && overlapH >= MIN_VISIBLE_HEIGHT && y >= s.y - 1
        }
        return if (visible) Placement(w, h, x, y, saved.maximized) else Placement(w, h, null, null, saved.maximized)
    }

    /** Bounds of the attached screens; empty when there is no display (headless) or the query fails. */
    fun currentScreens(): List<Bounds> = try {
        if (java.awt.GraphicsEnvironment.isHeadless()) emptyList()
        else java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map {
            val b = it.defaultConfiguration.bounds
            Bounds(b.x, b.y, b.width, b.height)
        }
    } catch (_: Throwable) { emptyList() }
}
