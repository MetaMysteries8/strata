package dev.doxi.strata

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object Store {
    const val MAX_ZIP = 600L * 1024 * 1024
    fun root(c: Context) = File(c.filesDir, "scans").apply { mkdirs() }
    fun dir(c: Context, id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid project ID" }
        return File(root(c), id).also { require(it.isDirectory) { "Project not found" } }
    }
    fun atomic(file: File, json: JSONObject) {
        val text=json.toString() ?: error("Cannot serialize ${file.name}")
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.outputStream().use { out -> out.write(text.toByteArray(Charsets.UTF_8));out.fd.sync() }
            check(tmp.renameTo(file)) { "Cannot save ${file.name}" }
        } finally { tmp.delete() }
    }
    fun create(c: Context, name: String): File {
        val id = UUID.randomUUID().toString(); val dir = File(root(c), id).apply { mkdirs() }
        atomic(File(dir,"meta.json"), JSONObject().put("id",id).put("name",name.trim().take(80).ifEmpty { "Untitled scan" }).put("created",System.currentTimeMillis()).put("frames",0).put("points",0).put("kind","capture"))
        return dir
    }
    fun meta(dir: File) = JSONObject(File(dir,"meta.json").readText())
    fun state(dir: File) = try { JSONObject(File(dir,"state.json").readText()) } catch (_:Exception) { JSONObject().put("phase","ready").put("message","Ready to train") }
    fun model(dir: File) = File(dir,"output").listFiles()?.filter { it.extension == "ply" && it.name !in listOf("dense-points.ply","points.ply","mesh.ply") && it.length() > 100 }?.maxByOrNull { it.lastModified() }
    fun mesh(dir:File)=File(dir,"output/mesh.glb").takeIf { it.isFile && it.length()>20 }
    fun validateModel(dir:File,file:File=model(dir) ?: error("No Gaussian PLY is available")):JSONObject {
        val cache=File(dir,"validation.json")
        val previous=try { JSONObject(cache.readText()) } catch(_:Exception) { null }
        if(previous?.optString("name")==file.name && previous.optLong("modified")==file.lastModified() && previous.optLong("bytes")==file.length()) {
            require(previous.optBoolean("valid")) { previous.optString("error") };return previous
        }
        try { val info=PlyFile.inspect(file).put("name",file.name).put("modified",file.lastModified()).put("bytes",file.length());atomic(cache,info);return info }
        catch(e:Exception) { atomic(cache,JSONObject().put("valid",false).put("error",e.message).put("name",file.name).put("modified",file.lastModified()).put("bytes",file.length()));throw e }
    }
    fun list(c: Context): JSONArray {
        val items = root(c).listFiles()?.mapNotNull { dir -> try {
            val model=model(dir)
            val validation=try { JSONObject(File(dir,"validation.json").readText()) } catch(_:Exception) { JSONObject() }
            val bad=model!=null && validation.optString("name")==model.name && validation.optLong("modified")==model.lastModified() && validation.has("valid") && !validation.optBoolean("valid")
            val remote=try { JSONObject(File(dir,"remote.json").readText()) } catch(_:Exception) { JSONObject() }
            val status=state(dir)
            if(dir.name==RemoteService.observedProject && RemoteService.lastContact>0) status.put("lastContact",RemoteService.lastContact)
            meta(dir).put("state",status).put("hasModel",model!=null && !bad).put("invalidModel",bad).put("modelError",if(bad) validation.optString("error") else "").put("hasMesh",mesh(dir)!=null).put("meshFormats",JSONArray(listOf("glb","obj","stl").filter {File(dir,"output/mesh.$it").isFile})).put("hasDensePoints",File(dir,"output/dense-points.ply").isFile).put("savedCheckpointStep",remote.optJSONObject("recovery")?.optLong("checkpoint_step",-1) ?: -1).put("canContinueSaved",remote.optJSONObject("recovery")?.optBoolean("resume_ready")==true).put("hasRemote",remote.has("id")).put("remoteId",remote.optString("id")).put("remoteMode",remote.optString("mode")).put("remoteDone",remote.optBoolean("complete")).put("hasDataset",File(dir,"dataset.zip").exists()).put("bytes",dir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        } catch (_:Exception) { null } }?.sortedByDescending { it.optLong("created") } ?: emptyList()
        return JSONArray(items)
    }
    fun pack(dir: File): File {
        val data = File(dir,"capture"); require(File(data,"transforms.json").exists()) { "Capture has no camera poses" }
        val file = File(dir,"dataset.zip"); val temp = File(dir,"dataset.zip.tmp")
        ZipOutputStream(temp.outputStream().buffered()).use { out ->
            data.walkTopDown().filter { it.isFile }.forEach {
                out.putNextEntry(ZipEntry(it.relativeTo(data).invariantSeparatorsPath)); it.inputStream().use { input -> input.copyTo(out) }; out.closeEntry()
            }
        }
        check(temp.renameTo(file)); return file
    }
    /** Only posed datasets are accepted. Plain images/video are never represented as reconstructed. */
    fun validateDataset(file: File): Int = ZipFile(file).use { zip ->
        require(file.length() <= MAX_ZIP) { "Dataset must be under 600 MiB" }
        val entries = zip.entries().asSequence().toList()
        require(entries.size <= 2500 && entries.sumOf { it.size.coerceAtLeast(0) } <= 2L*1024*1024*1024) { "Dataset expands beyond the import limit" }
        require(entries.all { !it.name.startsWith("/") && !it.name.contains('\\') && it.name.split('/').none { p -> p==".." } }) { "Unsafe ZIP path" }
        val manifest = entries.firstOrNull { it.name == "transforms.json" } ?: error("ZIP needs transforms.json at its root. Export a Strata or Nerfstudio dataset.")
        require(manifest.size in 1..8_000_000) { "Invalid pose manifest size" }
        val json = JSONObject(zip.getInputStream(manifest).bufferedReader().use { it.readText() })
        val frames = json.getJSONArray("frames"); require(frames.length() in 12..600) { "Use 12–600 posed images" }
        for(i in 0 until frames.length()) {
            val frame = frames.getJSONObject(i); val path = frame.getString("file_path").removePrefix("./")
            require(!path.contains("..") && zip.getEntry(path)!=null) { "Missing or unsafe image: $path" }
            val matrix=frame.getJSONArray("transform_matrix"); require(matrix.length()==4)
            for(row in 0..3) { val cells=matrix.getJSONArray(row); require(cells.length()==4); for(col in 0..3) require(cells.getDouble(col).isFinite()) }
            val width=frame.optInt("w",json.optInt("w")); val height=frame.optInt("h",json.optInt("h"))
            require(width in 1..12000 && height in 1..12000) { "Each image needs valid w/h intrinsics" }
            require(frame.optDouble("fl_x",json.optDouble("fl_x",0.0)) > 0 && frame.optDouble("fl_y",json.optDouble("fl_y",0.0)) > 0) { "Dataset needs calibrated focal lengths" }
        }
        frames.length()
    }
}
