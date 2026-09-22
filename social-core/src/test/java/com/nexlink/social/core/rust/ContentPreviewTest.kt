package com.nexlink.social.core.rust

import com.nexlink.social.core.session.TimelineContent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §14.1 / §14.2 — the one-line description of a message.
 *
 * Used by the inbox row and by the reply bar, which is the point: they used to
 * disagree, and the reply bar's version was the literal word "message" for
 * anything that was not text. Replying to a PDF looked like replying to nothing.
 */
class ContentPreviewTest {

    @Test fun `text passes through`() {
        assertEquals("hello", TimelineContent.Text("hello").toPreview())
    }

    @Test fun `newlines do not break the single-line row`() {
        assertEquals("a b", TimelineContent.Text("a\nb").toPreview())
    }

    /** The filename is the only part a reader can act on. */
    @Test fun `a file is described by its name`() {
        assertEquals(
            "report.pdf",
            TimelineContent.File("mxc://x", "report.pdf", 1234).toPreview(),
        )
    }

    /** A file that arrived without a name still has to say something. */
    @Test fun `a nameless file falls back to the word File`() {
        assertEquals("File", TimelineContent.File("mxc://x", "", null).toPreview())
        assertEquals("File", TimelineContent.File("mxc://x", "   ", null).toPreview())
    }

    @Test fun `media types are named`() {
        assertEquals("Photo", TimelineContent.Image("mxc://x", null, null, null).toPreview())
        assertEquals("Video", TimelineContent.Video("mxc://x", null, null).toPreview())
        assertEquals("Audio message", TimelineContent.Audio("mxc://x", null).toPreview())
    }

    @Test fun `a deleted message says so rather than showing nothing`() {
        assertEquals("Message deleted", TimelineContent.Redacted.toPreview())
    }
}
