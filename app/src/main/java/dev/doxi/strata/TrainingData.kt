package dev.doxi.strata

import java.io.File
import java.util.zip.*
import org.json.JSONObject

object TrainingData {
    fun prepare(project:File):File {
        val original=File(project,"dataset.zip")
        val work=File(project,"training-input").apply { mkdirs() }
        ZipFile(original).use { zip->
            val manifest=JSONObject(zip.getInputStream(zip.getEntry("transforms.json")).bufferedReader().use { it.readText() })
            val path=manifest.optString("ply_file_path").removePrefix("./")
            if(path.isEmpty()) return original
            val source=File(work,"original-seed.ply")
            zip.getInputStream(zip.getEntry(path) ?: error("Missing seed point cloud")).use { input->source.outputStream().use { input.copyTo(it) } }
            val seed=File(work,"finite-seed.ply");PlyFile.seedGaussian(source,seed)
            manifest.put("ply_file_path","strata_seed.ply")
            val prepared=File(work,"dataset.zip")
            ZipOutputStream(prepared.outputStream().buffered()).use { out->
                for(entry in zip.entries()) {
                    if(entry.name in listOf("transforms.json","strata_seed.ply")) continue
                    out.putNextEntry(ZipEntry(entry.name));if(!entry.isDirectory) zip.getInputStream(entry).use { it.copyTo(out) };out.closeEntry()
                }
                out.putNextEntry(ZipEntry("transforms.json"));out.write(manifest.toString().toByteArray());out.closeEntry()
                out.putNextEntry(ZipEntry("strata_seed.ply"));seed.inputStream().use { it.copyTo(out) };out.closeEntry()
            }
            return prepared
        }
    }
}
