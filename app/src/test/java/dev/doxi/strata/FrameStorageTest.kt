package dev.doxi.strata

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

class FrameStorageTest {
    private val matrix=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,2f,3f,4f,1f)
    private fun fixture()=javaClass.getResourceAsStream("/camera.jpeg")!!.use { it.readBytes() }
    private fun save(s:FrameStorage,jpeg:ByteArray=fixture())=s.save(jpeg,8,8,floatArrayOf(6f,6f),floatArrayOf(4f,4f),matrix,60.0)
    @Test fun savesImageAndCalibratedPoseThenRestoresCount() {
        val dir=kotlin.io.path.createTempDirectory("strata-frames").toFile()
        try {
            val s=FrameStorage(dir);assertEquals(1,save(s))
            assertArrayEquals(fixture(),File(dir,"images/frame_0000.jpg").readBytes())
            val frames=JSONObject(File(dir,"transforms.json").readText()).getJSONArray("frames")
            assertEquals(2.0,frames.getJSONObject(0).getJSONArray("transform_matrix").getJSONArray(0).getDouble(3),0.0)
            assertEquals(2,save(FrameStorage(dir)))
        } finally {dir.deleteRecursively()}
    }
    @Test fun failedManifestCommitLeavesNoCountOrOrphanImage() {
        val dir=kotlin.io.path.createTempDirectory("strata-fail").toFile()
        try {
            val s=FrameStorage(dir);File(dir,"transforms.json").mkdir()
            assertThrows(IllegalStateException::class.java){save(s)}
            assertEquals(0,s.frames.length());assertFalse(File(dir,"images/frame_0000.jpg").exists())
            assertFalse(File(dir,"images/frame_0000.jpg.part").exists())
        } finally {dir.deleteRecursively()}
    }
    @Test fun incompleteEncoderOutputIsRejectedBeforeAnyImageIsSaved() {
        val dir=kotlin.io.path.createTempDirectory("strata-incomplete").toFile()
        try { val s=FrameStorage(dir);assertThrows(IllegalArgumentException::class.java){save(s,byteArrayOf(-1,-40,1,2))};assertEquals(0,s.frames.length());assertFalse(File(dir,"transforms.json").exists()) }
        finally {dir.deleteRecursively()}
    }
    @Test fun invalidOptionalSeedsAreSkippedInsteadOfThrowing() {
        val focal=floatArrayOf(6f,6f);val principal=floatArrayOf(4f,4f)
        for(value in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY)) assertNull(Geometry.projectSeed(matrix,floatArrayOf(value,0f,-2f,1f),focal,principal,8,8))
        assertNull(Geometry.projectSeed(matrix,floatArrayOf(0f,0f,-2f,Float.NaN),focal,principal,8,8))
        val identity=matrix.copyOf().also { it[12]=0f;it[13]=0f;it[14]=0f }
        assertArrayEquals(intArrayOf(4,4),Geometry.projectSeed(identity,floatArrayOf(0f,0f,-2f,1f),focal,principal,8,8))
    }
}
