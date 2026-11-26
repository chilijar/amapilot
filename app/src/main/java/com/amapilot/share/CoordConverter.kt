package com.amapilot.share

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 简单经纬度点。 */
data class GeoPoint(val lat: Double, val lng: Double)

/** 坐标转换：WGS84 → GCJ-02（国测局偏移）、BD09 ↔ GCJ-02。高德底图用 GCJ-02，
 *  系统 GPS 与百度坐标须先转换。 */
object CoordConverter {

    private const val X_PI = Math.PI * 3000.0 / 180.0
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    /** WGS84 → GCJ-02；境外原样返回（偏移仅境内有效）。 */
    fun wgs84ToGcj02(wgsLat: Double, wgsLng: Double): GeoPoint {
        if (outOfChina(wgsLat, wgsLng)) return GeoPoint(wgsLat, wgsLng)
        val dLat = transformLat(wgsLng - 105.0, wgsLat - 35.0)
        val dLng = transformLng(wgsLng - 105.0, wgsLat - 35.0)
        val radLat = wgsLat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val lat = wgsLat + (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        val lng = wgsLng + (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * Math.PI)
        return GeoPoint(lat, lng)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
            0.2 * sqrt(Math.abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y +
            0.1 * sqrt(Math.abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }

    private fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271


    /** BD09 → GCJ-02。 */
    fun bd09ToGcj02(bdLat: Double, bdLng: Double): GeoPoint {
        val x = bdLng - 0.0065
        val y = bdLat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
        val theta = atan2(y, x) - 0.000003 * cos(x * X_PI)
        return GeoPoint(z * sin(theta), z * cos(theta))
    }


    /** GCJ-02 → BD09。 */
    fun gcj02ToBd09(gcjLat: Double, gcjLng: Double): GeoPoint {
        val z = sqrt(gcjLng * gcjLng + gcjLat * gcjLat) + 0.00002 * sin(gcjLat * X_PI)
        val theta = atan2(gcjLat, gcjLng) + 0.000003 * cos(gcjLng * X_PI)
        return GeoPoint(z * sin(theta) + 0.006, z * cos(theta) + 0.0065)
    }
}

/** 从分享文本提取坐标（`latlng=`/`location=`/裸坐标对）；含 baidu 时按 BD-09 转 GCJ-02。 */
fun parseCoordsFromText(text: String): GeoPoint? {
    for (m in COORD_PATTERN.findAll(text)) {
        val lat = m.groupValues[1].toDoubleOrNull() ?: continue
        val lng = m.groupValues[2].toDoubleOrNull() ?: continue
        // 合法经纬度范围（排除版本号等误匹配）
        if (lat in -90.0..90.0 && lng in -180.0..180.0) {
            return if (text.contains("baidu", ignoreCase = true)) {
                CoordConverter.bd09ToGcj02(lat, lng)
            } else {
                GeoPoint(lat, lng)
            }
        }
    }
    return null
}

private val COORD_PATTERN = Regex("""(?:latlng=|location=)?(-?\d{1,3}\.\d{1,7})[,%2C]+(-?\d{1,3}\.\d{1,7})""")
