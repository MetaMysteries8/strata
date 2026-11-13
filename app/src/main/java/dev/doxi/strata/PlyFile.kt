package dev.doxi.strata

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.json.JSONObject

/** Streaming PLY validation. Filenames alone never establish that a reconstruction succeeded. */
object PlyFile {
    data class Property(val name:String,val type:String,val bytes:Int)
    data class Header(val format:String,val vertices:Int,val properties:List<Property>,val offset:Long) {
        val isSplat get()=setOf("opacity","scale_0","scale_1","scale_2","rot_0","rot_1","rot_2","rot_3").all { name->properties.any { it.name==name } }
    }
    private val sizes=mapOf("char" to 1,"uchar" to 1,"int8" to 1,"uint8" to 1,"short" to 2,"ushort" to 2,"int16" to 2,"uint16" to 2,"int" to 4,"uint" to 4,"int32" to 4,"uint32" to 4,"float" to 4,"float32" to 4,"double" to 8,"float64" to 8)
    fun header(file:File):Header=RandomAccessFile(file,"r").use { f ->
        require(f.readLine()?.trim()=="ply") { "Not a PLY file: missing header" }
        var format="";var count=0;var element="";val properties=mutableListOf<Property>()
        while(f.filePointer<1024*1024) {
            val line=f.readLine() ?: error("Truncated PLY header")
            val p=line.trim().split(Regex("\\s+"));if(p.isEmpty()) continue
            when(p[0]) {
                "format"->format=p[1]
                "element"->{element=p[1];if(element=="vertex") count=p[2].toInt()}
                "property"->if(element=="vertex") { require(p.size==3 && sizes.containsKey(p[1])) { "Unsupported PLY vertex layout" };properties+=Property(p[2],p[1],sizes.getValue(p[1])) }
                "end_header"->{
                    require(count in 1..10_000_000 && listOf("x","y","z").all { key->properties.any { it.name==key } }) { "PLY has no valid 3D vertices" }
                    require(format in listOf("ascii","binary_little_endian","binary_big_endian")) { "Unsupported PLY encoding" }
                    if(format!="ascii") require(file.length()-f.filePointer>=count.toLong()*properties.sumOf { it.bytes }) { "Truncated PLY payload" }
                    return@use Header(format,count,properties,f.filePointer)
                }
            }
        }
        error("PLY header exceeds limit")
    }
    fun each(file:File,h:Header=header(file),visit:(DoubleArray)->Unit) {
        val stride=h.properties.sumOf { it.bytes }
        file.inputStream().buffered(64*1024).use { stream ->
            var left=h.offset;while(left>0) { val n=stream.skip(left);require(n>0) { "Truncated PLY" };left-=n }
            if(h.format=="ascii") {
                val reader=stream.bufferedReader(Charsets.US_ASCII)
                repeat(h.vertices) { val line=reader.readLine() ?: error("Truncated ASCII PLY");val values=line.trim().split(Regex("\\s+"));require(values.size>=h.properties.size);visit(DoubleArray(h.properties.size){values[it].toDouble()}) }
            } else {
                val input=DataInputStream(stream);val row=ByteArray(stride);val b=ByteBuffer.wrap(row).order(if(h.format=="binary_little_endian") ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
                repeat(h.vertices) {
                    input.readFully(row);b.position(0)
                    visit(DoubleArray(h.properties.size) { i->when(h.properties[i].type) {
                        "float","float32"->b.float.toDouble();"double","float64"->b.double
                        "uchar","uint8"->(b.get().toInt() and 255).toDouble();"char","int8"->b.get().toDouble()
                        "short","int16"->b.short.toDouble();"ushort","uint16"->(b.short.toInt() and 65535).toDouble()
                        "uint","uint32"->(b.int.toLong() and 0xffffffffL).toDouble();else->b.int.toDouble()
                    } })
                }
            }
        }
    }
    fun inspect(file:File,full:Boolean=true):JSONObject {
        val h=header(file);require(h.isSplat) { "This PLY is a point cloud or mesh, not a Gaussian splat" }
        val info=JSONObject().put("vertices",h.vertices).put("format",h.format)
        if(!full) return info
        val indices=h.properties.mapIndexed { i,p->p.name to i }.toMap()
        val lo=DoubleArray(3){Double.POSITIVE_INFINITY};val hi=DoubleArray(3){Double.NEGATIVE_INFINITY};var invalid=0
        each(file,h) { values->
            var valid=values.all { it.isFinite() }
            val q=(0..3).sumOf { values[indices.getValue("rot_$it")].pow(2) }
            if(q<1e-12 || !q.isFinite()) valid=false
            if(!valid) invalid++ else for((axis,name) in listOf("x","y","z").withIndex()) { val v=values[indices.getValue(name)];lo[axis]=min(lo[axis],v);hi[axis]=max(hi[axis],v) }
        }
        require(invalid==0) { "$invalid of ${h.vertices} Gaussians contain NaN/Infinity or zero rotations. Training failed; retrain from the original dataset." }
        require((0..2).maxOf { hi[it]-lo[it] }>1e-6) { "All Gaussian positions collapse to one point. Keep the original dataset and retrain." }
        return info.put("valid",true)
    }
    /** Explicit finite initial parameters bypass the experimental AAR's implicit point-cloud/KNN initialization. */
    fun seedGaussian(source:File,target:File) {
        val h=header(source)
        if(h.isSplat) { inspect(source);source.copyTo(target,true);return }
        val ix=h.properties.mapIndexed{i,p->p.name to i}.toMap()
        val rows=ArrayList<DoubleArray>()
        each(source,h) { row->if(row.all { it.isFinite() }) rows+=row }
        require(rows.size>=4) { "Not enough finite seed points" }
        val extent=listOf("x","y","z").maxOf { name->val i=ix.getValue(name);rows.maxOf { it[i] }-rows.minOf { it[i] } }
        require(extent>1e-6) { "Seed points collapse to one point; export the dataset for server reconstruction" }
        val scale=ln((extent/rows.size.toDouble().pow(1.0/3.0)/2).coerceIn(.001,.05)).toFloat()
        val names=listOf("x","y","z","f_dc_0","f_dc_1","f_dc_2","opacity","scale_0","scale_1","scale_2","rot_0","rot_1","rot_2","rot_3")
        target.outputStream().buffered().use { out->
            out.write(("ply\nformat binary_little_endian 1.0\nelement vertex ${rows.size}\n"+names.joinToString(""){"property float $it\n"}+"end_header\n").toByteArray(Charsets.US_ASCII))
            val buffer=ByteBuffer.allocate(names.size*4).order(ByteOrder.LITTLE_ENDIAN)
            for(row in rows) {
                buffer.clear();for(name in listOf("x","y","z")) buffer.putFloat(row[ix.getValue(name)].toFloat())
                for((channel,color) in listOf("red","green","blue").withIndex()) {
                    val dc=ix["f_dc_$channel"]?.let { row[it] } ?: (((ix[color]?.let { row[it]/255.0 } ?: .5)-.5)/.28209479177387814)
                    buffer.putFloat(dc.toFloat())
                }
                buffer.putFloat(-2.1972246f);repeat(3){buffer.putFloat(scale)};buffer.putFloat(1f);repeat(3){buffer.putFloat(0f)};out.write(buffer.array())
            }
        }
    }
}
