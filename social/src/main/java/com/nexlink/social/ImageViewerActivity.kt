package com.nexlink.social

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.nexlink.social.ui.chrome.Chrome
import com.nexlink.social.ui.chrome.Icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * §14.5.3 — opening a photo.
 *
 * Until this existed a received image was decoration: it rendered inline at up
 * to 320dp and a tap did nothing at all. No viewer, no zoom, no save, no share
 * — you could look at the thumbnail and that was the whole of it. A photo you
 * cannot open is barely a photo, and the failure was silent because there was
 * no click listener to fail.
 *
 * The bytes come from [SocialSession.loadMedia], which is the same decrypted
 * path the bubble uses — the image is never written anywhere on the way here.
 * It only touches disk when the user asks for it, and then only where they
 * choose: saving uses the document picker (as the export screen does) rather
 * than a storage permission, and sharing writes to the app's own cache and
 * hands out a scoped grant that dies with the receiving activity.
 */
class ImageViewerActivity : AppCompatActivity() {

    private var bitmap: Bitmap? = null
    private lateinit var image: ImageView
    private lateinit var status: TextView
    private val matrixValues = FloatArray(9)
    private var scale = 1f

    private val save = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/*")
    ) { uri ->
        val bmp = bitmap
        if (uri == null || bmp == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    } ?: error("no output stream")
                }.isSuccess
            }
            toast(if (ok) "Saved" else "Could not save that image")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mediaId = intent.getStringExtra(EXTRA_MEDIA_ID)
        val caption = intent.getStringExtra(EXTRA_CAPTION)

        val chrome = Chrome(this)
        val page = chrome.page(
            title = caption?.takeIf { it.isNotBlank() } ?: "Photo",
            onBack = { finish() },
            actions = listOf(
                Chrome.Action(Icon.Kind.DOWNLOAD, "Save to device") { saveImage() },
                Chrome.Action(Icon.Kind.SHARE, "Share") { shareImage() },
            ),
        )

        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            contentDescription = caption ?: "Photo, pinch to zoom"
        }
        status = TextView(this).apply {
            text = "Loading…"
            textSize = 15f
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }

        val holder = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(image)
            addView(status)
        }
        page.content.addView(holder)
        // The content column is sized by its children elsewhere; here the photo
        // should have the whole screen, so the column itself must stretch.
        (page.content.layoutParams as? LinearLayout.LayoutParams)?.weight = 1f
        setContentView(page.root)

        attachGestures()

        if (mediaId == null) { status.text = "That image is missing."; return }
        lifecycleScope.launch {
            val s = SessionProvider.manager(this@ImageViewerActivity).current()
            if (s == null) { status.text = "Not signed in."; return@launch }
            s.loadMedia(mediaId)
                .onSuccess { bytes ->
                    val bmp = withContext(Dispatchers.Default) {
                        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                    }
                    if (bmp == null) { status.text = "That image could not be read."; return@onSuccess }
                    bitmap = bmp
                    image.setImageBitmap(bmp)
                    status.visibility = View.GONE
                    image.post { fitToScreen() }
                }
                .onFailure {
                    // §14.2.2 — say what happened rather than leaving a blank box.
                    status.text = "That image could not be downloaded."
                }
        }
    }

    // ── zoom and pan ────────────────────────────────────────────────────────

    private fun fitToScreen() {
        val bmp = bitmap ?: return
        val vw = image.width.toFloat(); val vh = image.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val s = minOf(vw / bmp.width, vh / bmp.height)
        scale = s
        image.imageMatrix = Matrix().apply {
            setScale(s, s)
            postTranslate((vw - bmp.width * s) / 2f, (vh - bmp.height * s) / 2f)
        }
    }

    private fun attachGestures() {
        val m = Matrix()
        val scaleDetector = ScaleGestureDetector(this, object :
            ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                // Clamped: unbounded zoom-out loses the photo off-screen with no
                // way back, and unbounded zoom-in allocates a texture the GPU
                // refuses.
                val factor = d.scaleFactor.coerceIn(0.9f, 1.1f)
                val next = (scale * factor).coerceIn(baseScale() * 0.8f, baseScale() * 8f)
                val applied = next / scale
                scale = next
                image.imageMatrix = image.imageMatrix.also {
                    it.postScale(applied, applied, d.focusX, d.focusY)
                }
                image.invalidate()
                return true
            }
        })
        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scale > baseScale() * 1.05f) fitToScreen() else {
                    val f = baseScale() * 3f / scale
                    scale *= f
                    image.imageMatrix = image.imageMatrix.also { it.postScale(f, f, e.x, e.y) }
                    image.invalidate()
                }
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                image.imageMatrix = image.imageMatrix.also { it.postTranslate(-dx, -dy) }
                image.invalidate()
                return true
            }
        })
        image.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            gesture.onTouchEvent(ev)
            true
        }
    }

    private fun baseScale(): Float {
        val bmp = bitmap ?: return 1f
        val vw = image.width.toFloat(); val vh = image.height.toFloat()
        if (vw <= 0f || vh <= 0f) return 1f
        return minOf(vw / bmp.width, vh / bmp.height)
    }

    // ── save and share ──────────────────────────────────────────────────────

    private fun saveImage() {
        if (bitmap == null) { toast("Still loading"); return }
        save.launch("nexlink-${System.currentTimeMillis()}.png")
    }

    /**
     * Share through the app's own cache and a scoped grant.
     *
     * The decrypted bytes are NOT written anywhere until this moment, and the
     * copy lives in cacheDir behind a FileProvider — never on shared storage,
     * where every app on the phone could read a photo the user only meant to
     * forward once.
     */
    private fun shareImage() {
        val bmp = bitmap ?: run { toast("Still loading"); return }
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(cacheDir, "shared-media").apply {
                        mkdirs(); listFiles()?.forEach { it.delete() }
                    }
                    val f = File(dir, "photo-${System.currentTimeMillis()}.png")
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    androidx.core.content.FileProvider.getUriForFile(
                        this@ImageViewerActivity, "$packageName.fileprovider", f)
                }.getOrNull()
            }
            if (uri == null) { toast("Could not prepare that image"); return@launch }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                // The chooser renders its preview from clipData, not from
                // EXTRA_STREAM. Without it the sheet shows a blank document
                // icon for a photo, which reads as "this failed".
                clipData = android.content.ClipData.newUri(contentResolver, "Photo", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share photo"))
        }
    }

    private fun toast(s: String) =
        Snackbar.make(findViewById(android.R.id.content), s, Snackbar.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_MEDIA_ID = "media_id"
        private const val EXTRA_CAPTION = "caption"

        fun intent(ctx: Context, mediaId: String, caption: String?): Intent =
            Intent(ctx, ImageViewerActivity::class.java)
                .putExtra(EXTRA_MEDIA_ID, mediaId)
                .putExtra(EXTRA_CAPTION, caption)
    }
}
