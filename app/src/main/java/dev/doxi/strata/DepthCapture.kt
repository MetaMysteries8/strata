package dev.doxi.strata

import com.google.ar.core.Frame
import java.io.*
import java.nio.*
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.*

data class DepthFrame(val width:Int,val height:Int,val millimeters:ByteArray,val confidence:ByteArray,val focal:FloatArray,val principal:FloatArray,val pose:FloatArray,val points:FloatArray,val timestamp:Long)

object DepthCapture {
    fun acquire(frame:Frame):DepthFrame? {
        val depth=try { frame.acquireRawDepthImage16Bits() } catch(_:Exception) {return null}
        try {
            val certainty=try { frame.acquireRawDepthConfidenceImage() } catch(_:Exception) {return null}
            try {
                require(depth.width==certainty.width && depth.height==certainty.height)
                val w=depth.width;val h=depth.height;val raw=ByteArray(w*h*2);val confidence=ByteArray(w*h)
                val d=depth.planes[0];val c=certainty.planes[0];val db=d.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);val cb=c.buffer.duplicate()
                val baseD=db.position();val baseC=cb.position()
                for(y in 0 until h) for(x in 0 until w) {
                    val offset=y*w+x;val mm=db.getShort(baseD+y*d.rowStride+x*d.pixelStride).toInt() and 65535
                    raw[offset*2]=(mm and 255).toByte();raw[offset*2+1]=(mm ushr 8).toByte();confidence[offset]=cb.get(baseC+y*c.rowStride+x*c.pixelStride)
                }
                // Depth uses texture intrinsics scaled to its own resolution, not the CPU image's calibration.
                val k=frame.camera.textureIntrinsics;val dimensions=k.imageDimensions
                val f=k.focalLength;val p=k.principalPoint
                val focal=floatArrayOf(f[0]*w/dimensions[0],f[1]*h/dimensions[1]);val principal=floatArrayOf(p[0]*w/dimensions[0],p[1]*h/dimensions[1])
                val pose=FloatArray(16);frame.camera.pose.toMatrix(pose,0)
                val step=max(2,ceil(sqrt(w*h/6000.0)).toInt());val points=ArrayList<Float>()
                for(y in 0 until h step step) for(x in 0 until w step step) {
                    val i=y*w+x;val conf=(confidence[i].toInt() and 255)/255f
                    val meters=((raw[i*2].toInt() and 255) or ((raw[i*2+1].toInt() and 255) shl 8))*.001f
                    if(conf<.75f || meters !in .1f..8f) continue
                    val px=(x-principal[0])/focal[0]*meters;val py=-(y-principal[1])/focal[1]*meters;val pz=-meters
                    val world=floatArrayOf(pose[0]*px+pose[4]*py+pose[8]*pz+pose[12],pose[1]*px+pose[5]*py+pose[9]*pz+pose[13],pose[2]*px+pose[6]*py+pose[10]*pz+pose[14])
                    if(world.all {it.isFinite()}) {points.addAll(world.toList());points.add(conf)}
                }
                return DepthFrame(w,h,raw,confidence,focal,principal,pose,points.toFloatArray(),depth.timestamp)
            } finally {certainty.close()}
        } finally {depth.close()}
    }
}

/** Lossless grayscale PNG, retaining every millimeter rather than quantizing depth to 8 bits. */
object DepthPng {
    fun write(file:File,width:Int,height:Int,littleEndian:ByteArray,bits:Int=16) {
        require(bits==8 || bits==16);val bytes=bits/8;require(littleEndian.size==width*height*bytes)
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { out->
            out.write(byteArrayOf(-119,80,78,71,13,10,26,10))
            fun chunk(name:String,data:ByteArray) {val type=name.toByteArray(Charsets.US_ASCII);out.writeInt(data.size);out.write(type);out.write(data);val crc=CRC32();crc.update(type);crc.update(data);out.writeInt(crc.value.toInt())}
            val header=ByteArrayOutputStream();DataOutputStream(header).use {it.writeInt(width);it.writeInt(height);it.writeByte(bits);repeat(4){_->it.writeByte(0)}};chunk("IHDR",header.toByteArray())
            val encoded=ByteArrayOutputStream()
            DeflaterOutputStream(encoded).use { zip->for(y in 0 until height) {zip.write(0);val start=y*width*bytes;if(bits==8) zip.write(littleEndian,start,width) else for(x in 0 until width) {zip.write(littleEndian[start+x*2+1].toInt() and 255);zip.write(littleEndian[start+x*2].toInt() and 255)}} }
            chunk("IDAT",encoded.toByteArray());chunk("IEND",ByteArray(0))
        }
    }
}
