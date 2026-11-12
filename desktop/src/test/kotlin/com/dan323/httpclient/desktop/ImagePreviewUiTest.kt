package com.dan323.httpclient.desktop

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.viewmodel.MAX_PREVIEW_BYTES
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ImagePreviewUiTest {
    private fun png(width: Int = 4, height: Int = 3, format: String = "png"): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out)
        return out.toByteArray()
    }

    private fun outcome(body: ByteArray, type: String) = RunOutcome.Success(
        RequestTemplate("img", "GET", "http://x/pic"),
        HttpResponse(200, listOf(Header("Content-Type", type)), body, 5),
    )

    private fun androidx.compose.ui.test.ComposeUiTest.exists(tag: String) = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test fun decodesAnImageAndReportsItsSize() {
        val ok = decodeImage(png(4, 3)) as DecodedImage.Ok
        assertEquals(4, ok.width)
        assertEquals(3, ok.height)
    }

    @Test fun refusesGarbageAndTooLargeBodiesWithoutThrowing() {
        assertTrue((decodeImage(byteArrayOf(1, 2, 3)) as DecodedImage.Failed).reason.isNotBlank())
        assertTrue((decodeImage(ByteArray(MAX_PREVIEW_BYTES + 1)) as DecodedImage.Failed).reason.contains("larger than"))
    }

    @Test fun anImageResponseOpensOnThePreviewTab() = runComposeUiTest {
        setContent { ResponsePane(listOf(outcome(png(4, 3), "image/png"))) }
        waitUntil(timeoutMillis = 10_000) { exists("image-preview") }
        val info = onNodeWithTag("image-info").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)!!.joinToString("") { it.text }
        assertTrue(info.startsWith("PNG, 4 × 3 px"), info)
        onNodeWithTag("tab-preview").assertExists()
    }

    @Test fun aBodyThatIsNotAnImageExplainsItself() = runComposeUiTest {
        setContent { ResponsePane(listOf(outcome(byteArrayOf(1, 2, 3), "image/png"))) }
        waitUntil(timeoutMillis = 10_000) { exists("image-failed") }
    }

    @Test fun theBodyTabStillShowsTheRawResponseAndTextHasNoPreviewTab() = runComposeUiTest {
        setContent { ResponsePane(listOf(outcome(png(), "image/png"), outcome("hello".toByteArray(), "text/plain"))) }
        waitUntil(timeoutMillis = 10_000) { exists("image-preview") }
        assertEquals(1, onAllNodes(hasTestTag("tab-preview")).fetchSemanticsNodes().size)   // only the image has the tab
        onAllNodesWithText("Body")[0].performClick()                       // Body tab of the image: the raw text, no picture
        waitUntil(timeoutMillis = 10_000) { !exists("image-preview") }
        onNodeWithTag("tab-preview").performClick()
        waitUntil(timeoutMillis = 10_000) { exists("image-preview") }
    }

    @Test fun anEmptyImageBodyHasNoPreviewTab() = runComposeUiTest {
        setContent { ResponsePane(listOf(outcome(ByteArray(0), "image/png"))) }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("(empty body)").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(!exists("tab-preview"))
        assertTrue(!exists("image-failed"))
    }

    @Test fun aCutBodyIsNotDecodedAndSaysSo() = runComposeUiTest {
        setContent { ResponsePane(listOf(outcome(png(), "image/png")), bodyCutNote = "only 5 MB of 9 MB were kept") }
        waitUntil(timeoutMillis = 10_000) { exists("image-failed") }
        assertTrue(!exists("image-preview"))
    }

    @Test fun theDecodedImageIsKeptWhenTheTabChanges() = runComposeUiTest {
        val o = outcome(png(), "image/png")
        setContent { ResponsePane(listOf(o)) }
        waitUntil(timeoutMillis = 10_000) { exists("image-preview") }
        onAllNodesWithText("Body")[0].performClick()
        waitUntil(timeoutMillis = 10_000) { !exists("image-preview") }
        onNodeWithTag("tab-preview").performClick()
        waitForIdle()
        assertTrue(exists("image-preview"), "shown at once from the kept decode")
        assertTrue(!onAllNodesWithText("Decoding the image…").fetchSemanticsNodes().isNotEmpty())
    }
}
