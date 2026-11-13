package dev.doxi.strata

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.*
import java.io.*
import java.util.zip.InflaterInputStream

class PlyFileTest {
    private fun gaussian(file:File,count:Int=8,invalid:Boolean=false,collapsed:Boolean=false) {
        val names=listOf("x","y","z","f_dc_0","f_dc_1","f_dc_2","opacity","scale_0","scale_1","scale_2","rot_0","rot_1","rot_2","rot_3")
        file.outputStream().use { out->
            out.write(("ply\nformat binary_little_endian 1.0\nelement vertex $count\n"+names.joinToString(""){"property float $it\n"}+"end_header\n").toByteArray())
            repeat(count) { i->val b=ByteBuffer.allocate(names.size*4).order(ByteOrder.LITTLE_ENDIAN);b.putFloat(if(collapsed||invalid)0f else i*.1f);b.putFloat(0f);b.putFloat(0f);repeat(7){b.putFloat(if(invalid)Float.NaN else -2f)};b.putFloat(if(invalid)0f else 1f);repeat(3){b.putFloat(0f)};out.write(b.array()) }
        }
    }
    @Test fun rejectsReported161CollapsedNanGaussians() {
        val file=File.createTempFile("strata-bad",".ply")
        try {gaussian(file,161,true);val e=assertThrows(IllegalArgumentException::class.java){PlyFile.inspect(file)};assertTrue(e.message!!.contains("161 of 161"))}finally{file.delete()}
    }
    @Test fun rejectsCollapsedFiniteScene() {
        val file=File.createTempFile("strata-collapse",".ply");try{gaussian(file,collapsed=true);assertThrows(IllegalArgumentException::class.java){PlyFile.inspect(file)}}finally{file.delete()}
    }
    @Test fun validatesFiniteSplatAndDetectsTruncation() {
        val file=File.createTempFile("strata-good",".ply")
        try {gaussian(file);assertTrue(PlyFile.inspect(file).getBoolean("valid"));java.io.RandomAccessFile(file,"rw").use {it.setLength(file.length()-4)};assertThrows(IllegalArgumentException::class.java){PlyFile.inspect(file)}}finally{file.delete()}
    }
    @Test fun buildsExplicitFiniteGaussianSeedsFromColoredPoints() {
        val source=File.createTempFile("strata-seed",".ply");val output=File.createTempFile("strata-gaussian",".ply")
        try {
            source.writeText("ply\nformat ascii 1.0\nelement vertex 4\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n0 0 -1 255 0 0\n0.1 0 -1 0 255 0\n0 0.1 -1 0 0 255\n0.1 0.1 -1 255 255 255\n")
            PlyFile.seedGaussian(source,output);assertEquals(4,PlyFile.inspect(output).getInt("vertices"));val header=PlyFile.header(output);val r=header.properties.indexOfFirst {it.name=="rot_0"};PlyFile.each(output,header){assertEquals(1.0,it[r],0.0);assertTrue(it.all {n->n.isFinite()})}
        } finally {source.delete();output.delete()}
    }
    @Test fun depthPngRetains16BitMillimeterValues() {
        val file=File.createTempFile("strata-depth",".png")
        try {
            val b=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort(1000).putShort(6550).putShort(0).putShort(12345).array();DepthPng.write(file,2,2,b)
            val chunks=ByteArrayOutputStream();DataInputStream(file.inputStream()).use { input->input.skipBytes(8);while(input.available()>0){val n=input.readInt();val type=ByteArray(4).also {input.readFully(it)}.toString(Charsets.US_ASCII);val data=ByteArray(n).also {input.readFully(it)};input.readInt();if(type=="IHDR")assertEquals(16,data[8].toInt());if(type=="IDAT")chunks.write(data)} }
            val raster=InflaterInputStream(ByteArrayInputStream(chunks.toByteArray())).readBytes();assertEquals(10,raster.size);assertEquals(0,raster[0].toInt());assertEquals(1000,ByteBuffer.wrap(raster).order(ByteOrder.BIG_ENDIAN).getShort(1).toInt());assertEquals(12345,ByteBuffer.wrap(raster).order(ByteOrder.BIG_ENDIAN).getShort(8).toInt())
        }finally{file.delete()}
    }
}
