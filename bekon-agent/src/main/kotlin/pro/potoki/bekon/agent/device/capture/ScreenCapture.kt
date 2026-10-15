package pro.potoki.bekon.agent.device.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * FLAG_SECURE: frames come from a MediaProjection VirtualDisplay created without
 * VIRTUAL_DISPLAY_FLAG_SECURE (needs the system-only CAPTURE_SECURE_VIDEO_OUTPUT), so
 * SurfaceFlinger renders every FLAG_SECURE window as black. Secure content is never captured
 * or transmitted. Do not add VIRTUAL_DISPLAY_FLAG_SECURE or a non-projection screenshot path.
 */
class ScreenCapture(private val context: Context) : CaptureProvider {
    companion object {
        private const val TAG = "ScreenCapture"
        /** Fired when the OS ends the projection (lock screen, "Stop sharing" chip, etc.). */
        @Volatile var onSystemStop: (() -> Unit)? = null
        private const val TIMEOUT_MS = 5000L
        private const val SKIP_BLACK_MS = 1500L
        private const val BLACK_CHANNEL = 8
    }

    override var width: Int
    override var height: Int
    private val screenW: Int
    private val screenH: Int
    private var mediaProjectionData: Intent? = null
    private var mediaProjectionResultCode: Int = 0
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val captureThread = HandlerThread("ScreenCapture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    @Volatile private var skipBlackUntil = 0L
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            try { onSystemStop?.invoke() } catch (e: Exception) { Log.w(TAG, "onSystemStop: ${e.message}") }
            captureHandler.post {
                releasePreviewDisplay()
                mediaProjection = null
            }
        }
    }

    init {
        val (w, h) = DisplayMetricsHelper.realScreenSize(context)
        screenW = w
        screenH = h
        val preview = CapturePrefs.pixelSize(w, h, CapturePrefs.previewScale(context))
        width = preview.first
        height = preview.second
        Log.i(TAG, "MediaProjection mode: preview ${width}x${height}, screen ${screenW}x${screenH}")
    }

    /** Store permission data. start() uses it later. */
    fun setPermissionData(data: Intent, resultCode: Int) {
        mediaProjectionData = data
        mediaProjectionResultCode = resultCode
    }

    override fun start() {
        val data = mediaProjectionData ?: run {
            Log.w(TAG, "No permission data yet")
            return
        }
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            mgr.getMediaProjection(mediaProjectionResultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection: ${e.message}")
            return
        }
        if (mp == null) {
            Log.e(TAG, "getMediaProjection returned null")
            return
        }
        // Android 14+: a consent Intent is single-use; never replay it (a new grant is needed each time).
        if (android.os.Build.VERSION.SDK_INT >= 34) mediaProjectionData = null
        // Android 14+ requires a callback before createVirtualDisplay; harmless on 13/Itel.
        mp.registerCallback(projectionCallback, captureHandler)
        mediaProjection = mp
        bindPreviewDisplay()
        Log.i(TAG, "Virtual display created: ${width}x${height}")
    }

    /**
     * Android 14+ allows exactly ONE createVirtualDisplay per MediaProjection token; a second
     * call throws and the system stops the projection. So we keep a single full-resolution
     * virtual display for the whole session, cache the latest frame (static screens produce no
     * new frames), and scale in software.
     */
    @Volatile private var lastFrame: Bitmap? = null
    @Volatile private var lastFrameAt = 0L
    private val frameLock = Object()

    val isActive: Boolean get() = mediaProjection != null && virtualDisplay != null

    override fun capture(opts: CaptureOpts): CaptureFrame? {
        val (tw, th) = CapturePrefs.pixelSize(screenW, screenH, opts.scale)
        val full = latestBitmap() ?: return null
        val sized = if (full.width == tw && full.height == th) full
            else Bitmap.createScaledBitmap(full, tw, th, true)
        val bytes = encode(sized, opts.quality, opts.mime)
        if (sized !== full) sized.recycle()
        full.recycle()
        return CaptureFrame(bytes, tw, th, opts.mime)
    }

    /** Copy of the latest full-resolution frame (caller owns it), or null. */
    fun latestBitmap(): Bitmap? {
        if (mediaProjection == null) return null
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val needFresh = System.currentTimeMillis() < skipBlackUntil
            synchronized(frameLock) {
                val f = lastFrame
                if (f != null && !needFresh) return f.copy(Bitmap.Config.ARGB_8888, false)
            }
            Thread.sleep(50)
        }
        Log.w(TAG, "Capture timeout: no frame")
        return null
    }

    private fun onFrame(reader: ImageReader) {
        val img = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        val bmp = try { imageToBitmap(img) } finally { img.close() }
        if (System.currentTimeMillis() < skipBlackUntil && bmp.isNearlyBlack(BLACK_CHANNEL)) {
            bmp.recycle(); return
        }
        skipBlackUntil = 0L
        synchronized(frameLock) {
            lastFrame?.recycle()
            lastFrame = bmp
            lastFrameAt = System.currentTimeMillis()
        }
    }

    override fun onDisplayWoke() {
        skipBlackUntil = System.currentTimeMillis() + SKIP_BLACK_MS
    }

    private fun bindPreviewDisplay() {
        val mp = mediaProjection ?: return
        if (virtualDisplay != null) return
        val dpi = context.resources.displayMetrics.densityDpi
        width = screenW
        height = screenH
        val reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ onFrame(it) }, captureHandler)
        imageReader = reader
        virtualDisplay = try {
            mp.createVirtualDisplay(
                "Bekon", screenW, screenH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, captureHandler,
            )
        } catch (e: Exception) {
            Log.e(TAG, "VD: ${e.message}")
            reader.close()
            imageReader = null
            null
        }
    }

    private fun releasePreviewDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        synchronized(frameLock) { lastFrame?.recycle(); lastFrame = null }
    }

    private fun drain(reader: ImageReader?) {
        if (reader == null) return
        while (true) {
            val img = reader.acquireLatestImage() ?: break
            img.close()
        }
    }

    private fun imageToBitmap(img: Image): Bitmap {
        val planes = img.planes
        val buf = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * img.width
        val bitmap = Bitmap.createBitmap(
            img.width + rowPadding / pixelStride, img.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buf)
        return if (rowPadding == 0) bitmap else {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, img.width, img.height)
            bitmap.recycle()
            cropped
        }
    }

    private fun encode(bitmap: Bitmap, quality: Int, mime: String): ByteArray {
        val out = ByteArrayOutputStream()
        if (mime == CaptureFrame.PNG) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } else {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        return out.toByteArray()
    }

    override fun stop() {
        releasePreviewDisplay()
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) { }
        mediaProjection?.stop(); mediaProjection = null
        captureThread.quitSafely()
    }
}
