package com.kirogui.jetbrains.ui

import com.kirogui.jetbrains.acp.PromptImage
import com.kirogui.jetbrains.acp.promptContent
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImageAttachmentsTest {

    @Test
    fun `big images are scaled down, small ones left alone`() {
        assertEquals(800 to 600, ImageAttachments.fit(800, 600))
        assertEquals(2000 to 1000, ImageAttachments.fit(4000, 2000))
        assertEquals(1000 to 2000, ImageAttachments.fit(2000, 4000))
    }

    @Test
    fun `a pasted image becomes a png attachment with its size`() {
        val img = ImageAttachments.fromImage(BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB))
        assertNotNull(img)
        assertEquals("image/png", img.mime)
        assertEquals(300 to 200, img.width to img.height)
        assertEquals("Image · 300×200", img.label)
        assertTrue(img.bytes.size > 8 && img.bytes[1] == 'P'.code.toByte(), "PNG signature")
        assertTrue(ImageAttachments.thumbnailUrl(img)!!.startsWith("file:"))
    }

    @Test
    fun `file extensions are recognised`() {
        assertTrue(ImageAttachments.isImageFile("shot.PNG") && ImageAttachments.isImageFile("a/b.jpeg"))
        assertEquals(false, ImageAttachments.isImageFile("notes.txt"))
    }

    @Test
    fun `prompt carries text then image blocks`() {
        val blocks = promptContent("look", listOf(PromptImage("image/png", byteArrayOf(1, 2, 3))))
        assertEquals(2, blocks.size)
        assertEquals("text", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image", blocks[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", blocks[1].jsonObject["mimeType"]!!.jsonPrimitive.content)
        assertEquals("AQID", blocks[1].jsonObject["data"]!!.jsonPrimitive.content)
        // an image alone needs no empty text block; no image still sends the text
        assertEquals(1, promptContent("", listOf(PromptImage("image/png", byteArrayOf(1)))).size)
        assertEquals(1, promptContent("", emptyList()).size)
    }
}
