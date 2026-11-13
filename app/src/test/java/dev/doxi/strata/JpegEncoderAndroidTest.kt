package dev.doxi.strata

import android.graphics.BitmapFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JpegEncoderAndroidTest {
    @Test fun rgbFallbackCreatesDecodableJpegAndAndroidPoseManifest() {
        val w=16;val h=16;val nv=ByteArray(w*h*3/2){128.toByte()}
        val jpeg=JpegEncoder.fromRgb(nv,w,h)
        val bitmap=BitmapFactory.decodeByteArray(jpeg,0,jpeg.size)
        assertNotNull(bitmap);assertEquals(w,bitmap.width);assertEquals(h,bitmap.height);bitmap.recycle()
        val dir=kotlin.io.path.createTempDirectory("strata-android-save").toFile()
        try {
            val store=FrameStorage(dir)
            val pose=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
            assertEquals(1,store.save(jpeg,w,h,floatArrayOf(12f,12f),floatArrayOf(8f,8f),pose,30.0))
            val restored=FrameStorage(dir);assertEquals(1,restored.frames.length())
            assertTrue(File(dir,"images/frame_0000.jpg").length()>100)
        } finally {dir.deleteRecursively()}
    }
}
