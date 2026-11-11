package com.dan323.httpclient.viewmodel

/** Image types a UI can preview. SVG is not here: the decoders UIs use for bitmaps do not read it. */
enum class ImageFormat(val label: String, internal val mimeTypes: Set<String>) {
    PNG("PNG", setOf("image/png")),
    JPEG("JPEG", setOf("image/jpeg", "image/jpg", "image/pjpeg")),
    GIF("GIF", setOf("image/gif")),
    WEBP("WebP", setOf("image/webp")),
    BMP("BMP", setOf("image/bmp", "image/x-ms-bmp")),
}

/** Bodies larger than this (bytes) are not decoded for a preview. */
const val MAX_PREVIEW_BYTES = 20_000_000

/** Images with more pixels than this are not decoded: the decoded bitmap would be about four bytes per pixel. */
const val MAX_PREVIEW_PIXELS = 40_000_000L

/** The previewable image type named by a `Content-Type` value (parameters and case ignored), or null. */
fun imageFormatOf(contentType: String?): ImageFormat? {
    val mime = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
    return ImageFormat.entries.firstOrNull { mime in it.mimeTypes }
}

/** Why an image of [bytes] bytes and [width] x [height] pixels is not decoded, or null when it may be. */
fun previewRefusal(bytes: Int, width: Int, height: Int): String? = when {
    bytes > MAX_PREVIEW_BYTES -> "the image is larger than ${MAX_PREVIEW_BYTES / 1_000_000} MB"
    width <= 0 || height <= 0 -> "the image has no size"
    width.toLong() * height > MAX_PREVIEW_PIXELS -> "the image has more than ${MAX_PREVIEW_PIXELS / 1_000_000} million pixels"
    else -> null
}

/** `PNG, 800 x 600 px, 12 KB`. */
fun imageInfoLabel(format: ImageFormat, width: Int, height: Int, bytes: Int): String =
    "${format.label}, $width × $height px, ${sizeLabel(bytes)}"
