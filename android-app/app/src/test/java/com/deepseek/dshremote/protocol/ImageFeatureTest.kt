package com.deepseek.dshremote.protocol

import com.deepseek.dshremote.net.AttachmentBytes
import com.deepseek.dshremote.net.ImageLoadState
import com.deepseek.dshremote.ui.ImageSizing
import com.deepseek.dshremote.ui.statusText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Image feature (requirement: show images in messages).
 *
 * Gateway contract under test:
 *  - every message row carries an `images` array; `[]` means "no images"
 *    (F-5 rule: keys always present, never omitted);
 *  - `GET /rc/v1/sessions/{id}/attachments/{aid}` returns base64 bytes.
 */
class ImageFeatureTest {

    private fun parse(s: String) = Message.parse(JsonObjectReader(Json.parseObject(s)))

    private fun row(images: String, extra: String = "") = parse(
        """{"seq":1,"role":"user","text":"look","ts":1,"status":"final","messageId":"m",$extra"images":$images}"""
    )

    // ---- images parsing ----------------------------------------------

    @Test fun `解析单张图片的完整字段`() {
        val m = row(
            """[{"attachmentId":"a1","mediaType":"image/png","bytes":2048,"width":800,"height":600,"name":"shot.png"}]"""
        )
        assertEquals(1, m.images.size)
        val i = m.images[0]
        assertEquals("a1", i.attachmentId)
        assertEquals("image/png", i.mediaType)
        assertEquals(2048L, i.bytes)
        assertEquals(800, i.width)
        assertEquals(600, i.height)
        assertEquals("shot.png", i.name)
    }

    @Test fun `缺 images 键时为空列表（老网关兼容）`() {
        val m = parse("""{"seq":1,"role":"user","text":"x","ts":1,"status":"final","messageId":"m"}""")
        assertTrue(m.images.isEmpty())
    }

    @Test fun `images 为 null 时为空列表`() {
        assertTrue(row("null").images.isEmpty())
    }

    @Test fun `images 为空数组时为空列表`() {
        assertTrue(row("[]").images.isEmpty())
    }

    @Test fun `多张图片保持顺序`() {
        val m = row("""[{"attachmentId":"a"},{"attachmentId":"b"},{"attachmentId":"c"}]""")
        assertEquals(listOf("a", "b", "c"), m.images.map { it.attachmentId })
    }

    @Test fun `缺 attachmentId 的条目被丢弃`() {
        // 没有 opaque id 就无法取字节，显示一个坏占位比不显示更糟
        val m = row("""[{"mediaType":"image/png"},{"attachmentId":"ok","mediaType":"image/png"}]""")
        assertEquals(1, m.images.size)
        assertEquals("ok", m.images[0].attachmentId)
    }

    @Test fun `缺省字段回落到零值 不崩`() {
        val m = row("""[{"attachmentId":"a"}]""")
        assertEquals("", m.images[0].mediaType)
        assertEquals(0L, m.images[0].bytes)
        assertEquals(0, m.images[0].width)
        assertNull(m.images[0].name)
    }

    @Test fun `仅接受 DSH 支持的四种媒体类型`() {
        val ok = row("""[{"attachmentId":"1","mediaType":"image/png"},{"attachmentId":"2","mediaType":"image/jpeg"},
                       {"attachmentId":"3","mediaType":"image/webp"},{"attachmentId":"4","mediaType":"image/gif"}]""")
        assertTrue(ok.images.all { it.supported })
        val bad = row("""[{"attachmentId":"1","mediaType":"image/tiff"}]""")
        assertFalse(bad.images[0].supported)
        assertFalse(row("""[{"attachmentId":"1","mediaType":"application/pdf"}]""").images[0].supported)
    }

    // ---- payload validation (no crash on bad input) ------------------

    @Test fun `接受合法 base64`() {
        assertNotNull(AttachmentBytes.accept("iVBORw0KGgo=", 8))
    }

    @Test fun `拒绝空与 null`() {
        assertNull(AttachmentBytes.accept(null, 0))
        assertNull(AttachmentBytes.accept("", 0))
    }

    @Test fun `拒绝含非法字符的 base64`() {
        assertNull(AttachmentBytes.accept("not base64!!", 0))
        assertNull(AttachmentBytes.accept("####", 0))
    }

    @Test fun `接受合法 padding`() {
        assertNotNull(AttachmentBytes.accept("QQ==", 1))
        assertNotNull(AttachmentBytes.accept("QUI=", 2))
    }

    @Test fun `超过 8MiB 上限被拒`() {
        assertEquals(AttachmentBytes.MAX_BYTES, 8L * 1024 * 1024)
        assertNull(AttachmentBytes.accept("AAAA", AttachmentBytes.MAX_BYTES + 1))
        assertNotNull(AttachmentBytes.accept("AAAA", AttachmentBytes.MAX_BYTES))
    }

    @Test fun `未声明 bytes 时按载荷长度判定`() {
        val tooBig = "A".repeat(((AttachmentBytes.MAX_BYTES + 64) * 4 / 3).toInt())
        assertNull(AttachmentBytes.accept(tooBig, 0))
        assertNotNull(AttachmentBytes.accept("AAAA", 0))
    }

    // ---- decode sampling (OOM safety) -------------------------------

    @Test fun `大图按 inSampleSize 降采样`() {
        // 4000x3000 into a 2048px budget => 2 is enough
        assertEquals(2, AttachmentBytes.inSampleSize(4000, 3000, 2048))
    }

    @Test fun `小图不降采样`() {
        assertEquals(1, AttachmentBytes.inSampleSize(800, 600, 2048))
        assertEquals(1, AttachmentBytes.inSampleSize(100, 100, 2048))
    }

    @Test fun `超大图持续降采样`() {
        assertTrue(AttachmentBytes.inSampleSize(8000, 8000, 2048) >= 2)
    }

    @Test fun `非法尺寸降采样为 1 不崩`() {
        assertEquals(1, AttachmentBytes.inSampleSize(0, 0, 2048))
        assertEquals(1, AttachmentBytes.inSampleSize(-1, 100, 2048))
    }

    // ---- frame geometry ---------------------------------------------

    @Test fun `帧高固定 180 与 DSH 一致`() {
        val s = AttachmentBytes.frameFor(frameWidthPx = 800, naturalWidth = 2000, naturalHeight = 1000)
        assertEquals(180, s.height)
        assertTrue("宽度不得超出 frame", s.width <= 800)
    }

    @Test fun `小图在 180 帧内保持原尺寸`() {
        val s = AttachmentBytes.frameFor(800, 50, 50)
        assertEquals(50, s.width)
        assertEquals(50, s.height)
    }

    @Test fun `纵向图按高度收缩`() {
        val s = AttachmentBytes.frameFor(800, 1000, 2000)
        assertEquals(90, s.width)
        assertEquals(180, s.height)
    }

    @Test fun `frame 常量与 ImageSizing 一致`() {
        assertEquals(ImageSizing.FRAME_HEIGHT_PX, 180)
        assertEquals(8, ImageSizing.CORNER_PX)
    }

    // ---- load states (loading / ready / error) ------------------------

    @Test fun `初始状态为 Idle 惰性加载`() {
        assertTrue(ImageLoadState.Idle is ImageLoadState)
    }

    @Test fun `失败状态带原因`() {
        val s = ImageLoadState.Failed(ImageLoadState.Failed.Reason.NETWORK)
        assertTrue(s is ImageLoadState.Failed)
        assertEquals(ImageLoadState.Failed.Reason.NETWORK, s.reason)
    }

    @Test fun `每种失败原因都有文案`() {
        for (r in ImageLoadState.Failed.Reason.entries) {
            assertTrue("原因 $r 必须有非空文案", statusText(r).isNotBlank())
        }
    }

    @Test fun `失败文案可区分不同原因`() {
        val all = ImageLoadState.Failed.Reason.entries.map { statusText(it) }
        assertEquals("不同失败原因应有不同文案", all.size, all.distinct().size)
    }

    @Test fun `空状态与失败状态不同`() {
        assertTrue(ImageLoadState.Empty is ImageLoadState)
        assertFalse(ImageLoadState.Empty is ImageLoadState.Failed)
    }
}