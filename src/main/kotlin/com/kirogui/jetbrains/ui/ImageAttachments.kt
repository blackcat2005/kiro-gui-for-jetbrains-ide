package com.kirogui.jetbrains.ui

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.swing.ImageIcon

/** Turns clipboard images and image files into [Attachment.Image]s Kiro can be sent. */
object ImageAttachments {

    /** Longer sides are scaled down to this, so a retina screenshot doesn't become a huge request. */
    const val MAX_SIDE = 2000

    /** Above this many bytes a PNG is re-encoded as JPEG (when nothing is transparent). */
    private const val PNG_LIMIT = 1_500_000

    private val EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp")

    fun isImageFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /** Size after scaling to at most [max] on the longer side; unchanged when it already fits. */
    fun fit(width: Int, height: Int, max: Int = MAX_SIDE): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= max) return width to height
        val k = max.toDouble() / longest
        return maxOf(1, (width * k).toInt()) to maxOf(1, (height * k).toInt())
    }

    fun fromFile(file: File): Attachment.Image? =
        try {
            ImageIO.read(file)?.let { fromImage(it) }
        } catch (_: Exception) {
            null
        }

    /** Null when [image] has no pixels (a broken clipboard entry). */
    fun fromImage(image: java.awt.Image): Attachment.Image? {
        val w = image.getWidth(null)
        val h = image.getHeight(null)
        if (w <= 0 || h <= 0) return null
        val (tw, th) = fit(w, h)
        val bi = scaled(image, tw, th, BufferedImage.TYPE_INT_ARGB)
        val png = encode(bi, "png")
        if (png.size <= PNG_LIMIT || !isOpaque(bi)) return Attachment.Image("image/png", png, tw, th)
        val flat = BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB).also {
            val g = it.createGraphics()
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, tw, th)
            g.drawImage(bi, 0, 0, null)
            g.dispose()
        }
        val jpg = encodeJpeg(flat)
        return if (jpg.size < png.size) Attachment.Image("image/jpeg", jpg, tw, th) else Attachment.Image("image/png", png, tw, th)
    }

    /** A small PNG on disk for the transcript, whose HTML view can only show images by URL. */
    fun thumbnailUrl(a: Attachment.Image): String? {
        a.thumb?.let { return it.toURI().toString() }
        return try {
            val src = ImageIO.read(a.bytes.inputStream()) ?: return null
            val (w, h) = fit(src.width, src.height, 240)
            val file = File.createTempFile("kiro-image", ".png").also { it.deleteOnExit() }
            ImageIO.write(scaled(src, w, h, BufferedImage.TYPE_INT_ARGB), "png", file)
            a.thumb = file
            file.toURI().toString()
        } catch (_: Exception) {
            null
        }
    }

    /** Size of the thumbnail [thumbnailUrl] makes, for the `<img>` tag. */
    fun thumbnailSize(a: Attachment.Image): Pair<Int, Int> = fit(a.width, a.height, 240)

    /** The image scaled to fit [maxW] x [maxH], for the preview popup. */
    fun icon(a: Attachment.Image, maxW: Int, maxH: Int): ImageIcon? {
        val src = try {
            ImageIO.read(a.bytes.inputStream())
        } catch (_: Exception) {
            null
        } ?: return null
        val k = minOf(1.0, maxW.toDouble() / src.width, maxH.toDouble() / src.height)
        return ImageIcon(scaled(src, maxOf(1, (src.width * k).toInt()), maxOf(1, (src.height * k).toInt()), BufferedImage.TYPE_INT_ARGB))
    }

    private fun scaled(src: java.awt.Image, w: Int, h: Int, type: Int): BufferedImage =
        BufferedImage(w, h, type).also {
            val g = it.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src, 0, 0, w, h, null)
            g.dispose()
        }

    private fun isOpaque(bi: BufferedImage): Boolean {
        for (y in 0 until bi.height step 4) for (x in 0 until bi.width step 4) if ((bi.getRGB(x, y) ushr 24) != 0xFF) return false
        return true
    }

    private fun encode(bi: BufferedImage, format: String): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(bi, format, it) }.toByteArray()

    private fun encodeJpeg(bi: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.85f
        }
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(bi, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }
}
