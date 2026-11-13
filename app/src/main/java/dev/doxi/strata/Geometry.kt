package dev.doxi.strata

import kotlin.math.*

/** ARCore and Nerfstudio both use OpenGL camera-to-world (+X right, +Y up, -Z forward). */
object Geometry {
    fun matrixRows(columnMajor: FloatArray): List<List<Double>> {
        require(columnMajor.size == 16 && columnMajor.all { it.isFinite() })
        return (0..3).map { row -> (0..3).map { col -> columnMajor[col * 4 + row].toDouble() } }
    }
    fun distance(a: FloatArray, b: FloatArray): Float = sqrt((0..2).sumOf { (a[it] - b[it]).toDouble().pow(2) }).toFloat()
    fun rotationDegrees(a: FloatArray, b: FloatArray): Float {
        val dot = abs((0..3).sumOf { (a[it] * b[it]).toDouble() }).coerceIn(0.0, 1.0)
        return Math.toDegrees(2.0 * acos(dot)).toFloat()
    }
    fun shouldCapture(position: FloatArray, rotation: FloatArray, previousPosition: FloatArray?, previousRotation: FloatArray?): Boolean =
        previousPosition == null || previousRotation == null || distance(position, previousPosition) >= .035f || rotationDegrees(rotation, previousRotation) >= 5f

    fun projectSeed(view:FloatArray,point:FloatArray,focal:FloatArray,principal:FloatArray,w:Int,h:Int):IntArray? {
        if(point.size<4 || point.any { !it.isFinite() } || point[3]<.5f) return null
        val x=point[0];val y=point[1];val z=point[2]
        val cx=view[0]*x+view[4]*y+view[8]*z+view[12]
        val cy=view[1]*x+view[5]*y+view[9]*z+view[13]
        val cz=view[2]*x+view[6]*y+view[10]*z+view[14]
        if(!cx.isFinite() || !cy.isFinite() || !cz.isFinite() || cz>=-.1f || cz< -12f) return null
        val px=focal[0]*cx/-cz+principal[0];val py=principal[1]-focal[1]*cy/-cz
        if(!px.isFinite() || !py.isFinite()) return null
        val u=px.roundToInt();val v=py.roundToInt()
        return if(u in 0 until w && v in 0 until h) intArrayOf(u,v) else null
    }

    /** Read YUV_420_888 planes with their actual row/pixel strides, including padded camera buffers. */
    fun toNv21(w: Int, h: Int, planes: List<ByteArray>, rows: IntArray, pixels: IntArray): ByteArray {
        require(w > 0 && h > 0 && w % 2 == 0 && h % 2 == 0)
        val out = ByteArray(w * h * 3 / 2)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = planes[0][y * rows[0] + x * pixels[0]]
        var i = w * h
        for (y in 0 until h / 2) for (x in 0 until w / 2) {
            out[i++] = planes[2][y * rows[2] + x * pixels[2]]
            out[i++] = planes[1][y * rows[1] + x * pixels[1]]
        }
        return out
    }
    fun rgb(nv: ByteArray, w: Int, h: Int, x: Int, y: Int): IntArray {
        val color=argb(nv,w,h,x,y)
        return intArrayOf((color ushr 16) and 255,(color ushr 8) and 255,color and 255)
    }
    fun argb(nv:ByteArray,w:Int,h:Int,x:Int,y:Int):Int {
        val px = x.coerceIn(0, w - 1); val py = y.coerceIn(0, h - 1)
        val luma = (nv[py * w + px].toInt() and 255) - 16
        val uv = w * h + (py / 2) * w + (px / 2) * 2
        val v = (nv[uv].toInt() and 255) - 128; val u = (nv[uv + 1].toInt() and 255) - 128
        val r=((298*luma+409*v+128) shr 8).coerceIn(0,255)
        val g=((298*luma-100*u-208*v+128) shr 8).coerceIn(0,255)
        val b=((298*luma+516*u+128) shr 8).coerceIn(0,255)
        return (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }
    fun imageQuality(nv: ByteArray, w: Int, h: Int): Pair<Double, Double> {
        var sum = 0.0; var square = 0.0; var light = 0.0; var n = 0
        fun y(x: Int, row: Int) = nv[row * w + x].toInt() and 255
        for (row in 2 until h - 2 step 6) for (x in 2 until w - 2 step 6) {
            val center = y(x, row)
            val lap = y(x-1,row) + y(x+1,row) + y(x,row-1) + y(x,row+1) - 4 * center
            sum += lap; square += lap.toDouble() * lap; light += center; n++
        }
        return if(n == 0) 0.0 to 0.0 else (square / n - (sum / n).pow(2)) to light / n
    }
}
