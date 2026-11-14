package com.kirogui.jetbrains.ui

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The timeline's line and dot as ONE tiny PNG in the temp folder (per pair of colours), used as a CSS background in
 * Swing's HTML view, which has no way to draw a dot on top of a line otherwise.
 *
 * Line and dot must come out of the same bitmap. As two separate images they are scaled and rounded separately on a
 * scaled display (at 125% the line landed at x = 12.0 and the dot's centre at x = 14.0, a visible gap); in one image
 * they go through the same scaling, so the dot stays centred on the line at any display scale.
 *
 * The image is 20 px wide with the line at x = 10 and the dot at the top; it is tall enough for any one row and repeats
 * downwards (the dot would only come back in a row taller than [HEIGHT] px).
 */
object RailImages {
    private const val HEIGHT = 6000
    private val dir: File by lazy { File(System.getProperty("java.io.tmpdir"), "kirogui-rail").apply { mkdirs() } }

    /** URL of the image for a [dotHex] dot on a [lineHex] line (both RRGGBB). */
    fun rail(dotHex: String, lineHex: String): String {
        val f = File(dir, "rail2-$dotHex-$lineHex.png")
        if (!f.exists()) {
            try {
                ImageIO.write(draw(dotHex, lineHex), "png", f)
            } catch (_: Exception) {
            }
        }
        return f.toURI().toString()
    }

    private fun draw(dotHex: String, lineHex: String): BufferedImage {
        val img = BufferedImage(20, HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val line = Color.decode("#$lineHex").rgb or (0xFF shl 24)
        for (y in 0 until HEIGHT) img.setRGB(10, y, line)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.decode("#$dotHex")
        g.fillOval(6, 3, 9, 9) // x 6..15: centre 10.5, the middle of the line's pixel column 10
        g.dispose()
        return img
    }
}
