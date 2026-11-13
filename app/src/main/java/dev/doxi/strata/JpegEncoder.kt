package dev.doxi.strata

import android.graphics.*
import java.io.ByteArrayOutputStream

object JpegEncoder {
    fun encode(nv:ByteArray,width:Int,height:Int):ByteArray {
        require(width>0 && height>0 && width%2==0 && height%2==0 && nv.size==width*height*3/2) { "Invalid NV21 image dimensions" }
        // Some device builds fail the YUV encoder. Its partial output is discarded before trying Bitmap.
        try {
            val out=ByteArrayOutputStream()
            if(YuvImage(nv,ImageFormat.NV21,width,height,intArrayOf(width,width)).compressToJpeg(Rect(0,0,width,height),94,out)) {
                val bytes=out.toByteArray();if(complete(bytes)) return bytes
            }
        } catch (_:RuntimeException) { /* Use the independent RGB encoder below. */ }
        return fromRgb(nv,width,height)
    }
    internal fun fromRgb(nv:ByteArray,width:Int,height:Int):ByteArray {
        val colors=IntArray(width*height)
        for(y in 0 until height) for(x in 0 until width) {
            colors[y*width+x]=Geometry.argb(nv,width,height,x,y)
        }
        val bitmap=Bitmap.createBitmap(colors,width,height,Bitmap.Config.ARGB_8888)
        try {
            val out=ByteArrayOutputStream();check(bitmap.compress(Bitmap.CompressFormat.JPEG,94,out)) { "Both JPEG encoders failed" }
            return out.toByteArray().also { check(complete(it)) { "RGB JPEG encoder returned an incomplete image" } }
        } finally { bitmap.recycle() }
    }
    private fun complete(b:ByteArray)=b.size>=4 && b[0]==0xff.toByte() && b[1]==0xd8.toByte() && b[b.lastIndex-1]==0xff.toByte() && b.last()==0xd9.toByte()
}
