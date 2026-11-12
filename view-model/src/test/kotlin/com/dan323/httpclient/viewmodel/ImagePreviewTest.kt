package com.dan323.httpclient.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImagePreviewTest {
    @Test fun formatsComeFromTheContentType() {
        assertEquals(ImageFormat.PNG, imageFormatOf("image/png"))
        assertEquals(ImageFormat.JPEG, imageFormatOf("IMAGE/JPEG; q=1"))
        assertEquals(ImageFormat.JPEG, imageFormatOf("image/jpg"))
        assertEquals(ImageFormat.GIF, imageFormatOf(" image/gif "))
        assertEquals(ImageFormat.WEBP, imageFormatOf("image/webp"))
        assertEquals(ImageFormat.BMP, imageFormatOf("image/x-ms-bmp"))
        assertNull(imageFormatOf("image/svg+xml"))
        assertNull(imageFormatOf("text/plain"))
        assertNull(imageFormatOf(null))
    }

    @Test fun largeAndOddImagesAreRefusedWithAReason() {
        assertNull(bodyRefusal(MAX_PREVIEW_BYTES))
        assertTrue(bodyRefusal(MAX_PREVIEW_BYTES + 1)!!.contains("larger than 20 MB"))
        assertNull(previewRefusal(1000, 100, 100))
        assertTrue(previewRefusal(MAX_PREVIEW_BYTES + 1, 10, 10)!!.contains("larger than 20 MB"))
        assertTrue(previewRefusal(10, 0, 10)!!.contains("no size"))
        assertTrue(previewRefusal(10, 10, -1)!!.contains("no size"))
        assertTrue(previewRefusal(10, 10_000, 10_000)!!.contains("million pixels"))
        assertNull(previewRefusal(10, 6_000, 6_000))     // exactly under the pixel limit
    }

    @Test fun theInfoLabelNamesTypeSizeAndBytes() {
        assertEquals("PNG, 800 × 600 px, 12.0 KB", imageInfoLabel(ImageFormat.PNG, 800, 600, 12_288))
    }
}
