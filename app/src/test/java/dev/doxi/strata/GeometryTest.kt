package dev.doxi.strata

import org.junit.Assert.*
import org.junit.Test

class GeometryTest {
    @Test fun cameraToWorldIsTransposedWithoutAxisFlip() {
        val input=floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 2f,3f,4f,1f)
        val rows=Geometry.matrixRows(input)
        assertEquals(listOf(1.0,0.0,0.0,2.0),rows[0])
        assertEquals(listOf(0.0,1.0,0.0,3.0),rows[1])
        assertEquals(listOf(0.0,0.0,1.0,4.0),rows[2])
        assertEquals(listOf(0.0,0.0,0.0,1.0),rows[3])
    }
    @Test fun redundantFramesAreSkippedButTranslationOrRotationQualifies() {
        val origin=floatArrayOf(0f,0f,0f);val q=floatArrayOf(0f,0f,0f,1f)
        assertTrue(Geometry.shouldCapture(origin,q,null,null))
        assertFalse(Geometry.shouldCapture(floatArrayOf(.001f,0f,0f),q,origin,q))
        assertTrue(Geometry.shouldCapture(floatArrayOf(.04f,0f,0f),q,origin,q))
        val rotated=floatArrayOf(0f,.0871557f,0f,.9961947f)
        assertTrue(Geometry.shouldCapture(origin,rotated,origin,q))
        assertEquals(0f,Geometry.rotationDegrees(q,floatArrayOf(0f,0f,0f,-1f)),.001f)
    }
    @Test fun paddedInterleavedYuvPlanesProduceCorrectNv21Order() {
        val y=byteArrayOf(1,2,3,4,99,99,5,6,7,8,99,99)
        val u=byteArrayOf(20,99,21);val v=byteArrayOf(30,99,31)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8,30,20,31,21),Geometry.toNv21(4,2,listOf(y,u,v),intArrayOf(6,4,4),intArrayOf(1,2,2)))
    }
    @Test fun neutralYuvProducesNeutralRgb() {
        val nv=ByteArray(6){128.toByte()};val rgb=Geometry.rgb(nv,2,2,0,0)
        assertEquals(rgb[0],rgb[1]);assertEquals(rgb[1],rgb[2])
        assertTrue(rgb[0] in 125..135)
    }
    @Test fun blankFramesHaveNoSharpness() {
        val nv=ByteArray(32*24*3/2){100};val quality=Geometry.imageQuality(nv,32,24)
        assertEquals(0.0,quality.first,0.0001);assertEquals(100.0,quality.second,0.0001)
    }
}
