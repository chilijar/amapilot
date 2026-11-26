package com.amapilot.share

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * 高德分享链接解析：任意 amap.com 域（surl 短链 / uri 直链 / wb、m 落地页）。
 * 重定向链 surl → wb（`p=lat,lng,…`）→ m/callAPP（`android=`/`ios=` 内嵌）→ 壳页，
 * 故须**逐跳**试解析，不能只解析最终 URL（壳页 HTML 无坐标）。
 *
 * 参数形态：uri 为 `position=lng,lat`（经度在前）；wb/androidamap 为
 * `p=poiid,lat,lng[,name[,address]]`（纬度在前）；`coordinate` 标识坐标系
 * （默认 GCJ-02，wgs84/baidu 需转换）。
 */
object AmapShareResolver {

    /** 解析结果：坐标 + POI 名（可空）。 */
    data class Resolved(val point: GeoPoint, val name: String?)

    /** 任意高德链接或腾讯地图链接（apis.map.qq.com，微信位置分享载荷）。 */
    private val MAP_URL = Regex("""https?://([\w.-]*amap\.com|apis\.map\.qq\.com)/\S+""")

    /** 分享文本是否含高德链接。 */
    fun isAmapShare(text: String): Boolean = MAP_URL.containsMatchIn(text)

    /** 从分享文本解析位置与 POI 名：先试参数直解，失败再网络展开（≤5 跳逐跳试解析），
     *  最后落地页 HTML 兜底。 */
    fun resolveNamed(text: String): Resolved? {
        for (m in MAP_URL.findAll(text)) {
            parseAmapUrl(m.value)?.let { p ->
                return Resolved(p, nameFromUrl(m.value))
            }
        }
        MAP_URL.findAll(text).firstOrNull()?.let { return expand(it.value) }
        return null
    }

    /** [resolveNamed] 的坐标投影（兼容旧调用点）。 */
    fun resolve(text: String): GeoPoint? = resolveNamed(text)?.point

    /** 展开短链：跟随重定向；每跳 URL 先试参数解析，末跳落地页 HTML 兜底。 */
    private fun expand(shortUrl: String): Resolved? {
        var url = shortUrl
        repeat(5) {
            val conn = open(url)
            try {
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", UA)
                when (conn.responseCode / 100) {
                    3 -> {
                        val location = conn.getHeaderField("Location") ?: return null
                        url = URL(URL(url), location).toString()
                        parseAmapUrl(url)?.let { p ->
                            return Resolved(p, nameFromUrl(url))
                        }
                    }
                    else -> {
                        // 落地页：优先解析最终 URL，失败再扫 HTML
                        parseAmapUrl(url)?.let { p ->
                            return Resolved(p, nameFromUrl(url))
                        }
                        val html = conn.inputStream.bufferedReader().use { it.readText() }
                        return parseFromHtml(html)?.let { Resolved(it, null) }
                    }
                }
            } catch (_: Exception) {
                return null
            } finally {
                conn.disconnect()
            }
        }
        return parseAmapUrl(url)?.let { Resolved(it, nameFromUrl(url)) }
    }

    /** 落地页 HTML 兜底：跳转 URL 或内嵌坐标。 */
    private fun parseFromHtml(html: String): GeoPoint? {
        MAP_URL.findAll(html).firstOrNull()?.let { return parseAmapUrl(it.value) }
        // 裸坐标对（经度在前的高德 JSON：lon/longitude 在前）
        for (m in COORD_PAIR.findAll(html)) {
            val a = m.groupValues[1].toDoubleOrNull() ?: continue
            val b = m.groupValues[2].toDoubleOrNull() ?: continue
            // 高德页面 JSON 多为 lon,lat 顺序
            val (lat, lng) = if (a in -90.0..90.0 && b !in -90.0..90.0) a to b else b to a
            if (lat in -90.0..90.0 && lng in -180.0..180.0) return GeoPoint(lat, lng)
        }
        return null
    }

    /** 解析单条 amap 链接的查询参数（见类注释参数形态）。 */
    internal fun parseAmapUrl(urlStr: String): GeoPoint? {
        val url = runCatching { URL(urlStr) }.getOrNull() ?: return null
        return parseAmapQuery(url.query ?: return null)
    }

    private fun parseAmapQuery(query: String): GeoPoint? {
        val params = parseQuery(query)
        // 腾讯地图 uri：marker=coord=lat,lng;title=..;addr=..（GCJ-02 纬在前）
        params["marker"]?.let { marker ->
            parseQqMarkerFields(marker)["coord"]?.let { coord ->
                val lat = coord.split(",").getOrNull(0)?.toDoubleOrNull() ?: return@let null
                val lng = coord.split(",").getOrNull(1)?.toDoubleOrNull() ?: return@let null
                return convert(lat, lng, null) // 腾讯 = GCJ-02 直用
            }
        }
        // uri.amap.com：position=lng,lat（经度在前）
        params["position"]?.let { pos ->
            val lng = pos.split(",").getOrNull(0)?.toDoubleOrNull() ?: return@let null
            val lat = pos.split(",").getOrNull(1)?.toDoubleOrNull() ?: return@let null
            return convert(lat, lng, params["coordinate"])
        }
        // wb.amap.com / androidamap：p=poiid,lat,lng[,name[,address]]（纬度在前）
        params["p"]?.let { p ->
            val seg = p.split(",")
            val lat = seg.getOrNull(1)?.toDoubleOrNull() ?: return@let null
            val lng = seg.getOrNull(2)?.toDoubleOrNull() ?: return@let null
            return convert(lat, lng, params["coordinate"])
        }
        // m.amap.com/callAPP：android=androidamap?...p=...（编码一层）
        params["android"]?.let { android ->
            val decoded = decode(android) ?: return@let null
            val q = decoded.substringAfter('?', "")
            if (q.isNotBlank()) parseAmapQuery(q)?.let { return it }
        }
        // m.amap.com/callAPP：ios=multiPointShow?...q=lat,lng,…（编码一层）
        params["ios"]?.let { ios ->
            val decoded = decode(ios) ?: return@let null
            val m = Regex("""(-?\d{1,3}\.\d{4,8})(?:%2C|,)(-?\d{1,3}\.\d{4,8})""")
                .find(decoded) ?: return@let null
            val lat = m.groupValues[1].toDoubleOrNull() ?: return@let null
            val lng = m.groupValues[2].toDoubleOrNull() ?: return@let null
            return convert(lat, lng, params["coordinate"])
        }
        return null
    }

    /** POI 名：name 参数 / 腾讯 marker title / p 参数第 4 段 / android 内嵌 p 第 4 段 / title 参数。 */
    internal fun nameFromUrl(urlStr: String): String? {
        val url = runCatching { URL(urlStr) }.getOrNull() ?: return null
        val params = parseQuery(url.query ?: return null)
        params["name"]?.let { decode(it)?.takeIf { n -> n.isNotBlank() }?.let { n -> return n } }
        params["marker"]?.let { marker ->
            parseQqMarkerFields(marker)["title"]?.takeIf { n -> n.isNotBlank() }
                ?.let { n -> return n }
        }
        params["title"]?.let { decode(it)?.takeIf { n -> n.isNotBlank() }?.let { n -> return n } }
        params["p"]?.let { p ->
            p.split(",").getOrNull(3)?.let {
                decode(it)?.takeIf { n -> n.isNotBlank() }?.let { n -> return n }
            }
        }
        params["android"]?.let { android ->
            val q = decode(android)?.substringAfter('?', "") ?: return@let null
            parseQuery(q)["p"]?.split(",")?.getOrNull(3)?.let {
                decode(it)?.takeIf { n -> n.isNotBlank() }?.let { n -> return n }
            }
        }
        return null
    }

    /** 腾讯地图 marker 参数内层字段（分号分隔，整体可能 URL 编码一层）。 */
    internal fun parseQqMarkerFields(marker: String): Map<String, String> {
        val decoded = decode(marker) ?: return emptyMap()
        return decoded.split(";").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()

    private fun decode(s: String): String? =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrNull()

    private fun convert(lat: Double, lng: Double, coordinate: String?): GeoPoint? {
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return when (coordinate?.lowercase()) {
            "wgs84" -> CoordConverter.wgs84ToGcj02(lat, lng)
            "baidu", "bd09" -> CoordConverter.bd09ToGcj02(lat, lng)
            else -> GeoPoint(lat, lng) // gaode / 默认均为 GCJ-02
        }
    }

    private fun open(url: String): HttpURLConnection =
        URL(url).openConnection() as HttpURLConnection

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    private val COORD_PAIR = Regex("""(-?\d{1,3}\.\d{4,8})[,%2C\s]+(-?\d{1,3}\.\d{4,8})""")
}
