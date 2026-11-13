package dev.doxi.strata

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Commit a camera image and its calibration together; a failed write never increments the frame count. */
class FrameStorage(private val capture: File) {
    init { check(capture.isDirectory || capture.mkdirs()) { "Cannot create capture directory" } }
    private val manifest=File(capture,"transforms.json")
    var frames:JSONArray=if(manifest.exists()) JSONObject(manifest.readText()).getJSONArray("frames") else JSONArray()
        private set

    fun save(jpeg:ByteArray,width:Int,height:Int,focal:FloatArray,principal:FloatArray,matrix:FloatArray,sharpness:Double):Int {
        require(width>0 && height>0 && focal.size==2 && principal.size==2)
        require(focal.all { it.isFinite() && it>0 } && principal.all { it.isFinite() } && sharpness.isFinite()) { "Invalid camera calibration" }
        require(jpeg.size>=4 && jpeg[0]==0xff.toByte() && jpeg[1]==0xd8.toByte() && jpeg[jpeg.lastIndex-1]==0xff.toByte() && jpeg.last()==0xd9.toByte()) { "JPEG encoding produced an incomplete image" }
        val rows=JSONArray()
        for(values in Geometry.matrixRows(matrix)) { val row=JSONArray(); for(value in values) row.put(value); rows.put(row) }
        val images=File(capture,"images");check(images.isDirectory || images.mkdirs()) { "Cannot create images directory" }
        val name="frame_"+frames.length().toString().padStart(4,'0')+".jpg"
        val image=File(images,name);val temp=File(images,"$name.part")
        val next=JSONArray(frames.toString()).put(JSONObject().put("file_path","images/$name").put("w",width).put("h",height).put("fl_x",focal[0].toDouble()).put("fl_y",focal[1].toDouble()).put("cx",principal[0].toDouble()).put("cy",principal[1].toDouble()).put("transform_matrix",rows).put("sharpness",sharpness))
        var committed=false
        try {
            temp.outputStream().use { out -> out.write(jpeg);out.fd.sync() }
            check(temp.renameTo(image)) { "Cannot commit image file" }
            Store.atomic(manifest,JSONObject().put("camera_model","OPENCV").put("frames",next))
            frames=next;committed=true
        } finally { temp.delete();if(!committed) image.delete() }
        return frames.length()
    }
    fun addDepth(depth:DepthFrame) {
        require(frames.length()>0)
        val stem="frame_"+(frames.length()-1).toString().padStart(4,'0')
        DepthPng.write(File(capture,"depth/$stem.png"),depth.width,depth.height,depth.millimeters)
        DepthPng.write(File(capture,"depth/${stem}_confidence.png"),depth.width,depth.height,depth.confidence,8)
        val calibration=JSONObject().put("w",depth.width).put("h",depth.height).put("fl_x",depth.focal[0].toDouble()).put("fl_y",depth.focal[1].toDouble()).put("cx",depth.principal[0].toDouble()).put("cy",depth.principal[1].toDouble())
        frames.getJSONObject(frames.length()-1).put("depth_file_path","depth/$stem.png").put("depth_confidence_file_path","depth/${stem}_confidence.png").put("depth_intrinsics",calibration).put("depth_transform_matrix",JSONArray(Geometry.matrixRows(depth.pose)))
        Store.atomic(manifest,JSONObject().put("camera_model","OPENCV").put("depth_unit_scale_factor",.001).put("frames",frames))
    }
}
