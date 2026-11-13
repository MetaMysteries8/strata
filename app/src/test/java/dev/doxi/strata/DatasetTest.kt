package dev.doxi.strata

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.*

class DatasetTest {
    private fun dataset(extraPath:String?=null,missingImage:Boolean=false,badPose:Boolean=false):File {
        val file=File.createTempFile("strata-test",".zip")
        val frames=JSONArray()
        for(i in 0..11) frames.put(JSONObject().put("file_path","images/$i.jpg").put("transform_matrix",JSONArray(if(badPose) "[[1,0],[0,1]]" else "[[1,0,0,0],[0,1,0,0],[0,0,1,0],[0,0,0,1]]")))
        val manifest=JSONObject().put("w",640).put("h",480).put("fl_x",500).put("fl_y",500).put("frames",frames)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("transforms.json"));zip.write(manifest.toString().toByteArray());zip.closeEntry()
            for(i in 0..if(missingImage)10 else 11){zip.putNextEntry(ZipEntry("images/$i.jpg"));zip.write(byteArrayOf(1,2,3));zip.closeEntry()}
            if(extraPath!=null){zip.putNextEntry(ZipEntry(extraPath));zip.write(byteArrayOf(0));zip.closeEntry()}
        }
        return file
    }
    @Test fun acceptsCompletePosedDataset(){val file=dataset();try{assertEquals(12,Store.validateDataset(file))}finally{file.delete()}}
    @Test fun rejectsZipSlipEvenInUnusedEntries(){val file=dataset(extraPath="../escape");try{assertThrows(IllegalArgumentException::class.java){Store.validateDataset(file)}}finally{file.delete()}}
    @Test fun rejectsMissingImage(){val file=dataset(missingImage=true);try{assertThrows(IllegalArgumentException::class.java){Store.validateDataset(file)}}finally{file.delete()}}
    @Test fun rejectsMalformedCameraMatrix(){val file=dataset(badPose=true);try{assertThrows(IllegalArgumentException::class.java){Store.validateDataset(file)}}finally{file.delete()}}
}
