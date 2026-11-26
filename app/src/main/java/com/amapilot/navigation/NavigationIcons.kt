package com.amapilot.navigation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory

/** 官方 Material navigation 图标路径数据（24x24 viewport，机头朝上=北）。 */
private const val NAVIGATION_PATH_DATA = "M12,2L4.5,20.29l0.71,0.71L12,18l6.79,3 0.71,-0.71z"

/** 步骤预览路口标记：白芯+蓝环圆点（≈路线线宽），蓝边使其在蓝色路线上更清晰。 */
fun stepDotDescriptor(): BitmapDescriptor {
    val size = 24
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val c = size / 2f
    val ring = 3.5f
    val blue = Color.rgb(0x1A, 0x73, 0xE8)
    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = blue
        style = Paint.Style.STROKE
        strokeWidth = ring
    }
    canvas.drawCircle(c, c, c - ring / 2f - 0.5f, ringPaint)
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(c, c, c - ring - 0.5f, fillPaint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

/** 导航/巡航箭头：官方 Material navigation 纸飞机（蓝底白描边，0° 指北），
 *  配合 Marker.setRotateAngle 指向行进方向。 */
fun arrowDescriptor(context: Context, sizeScale: Float = 1f): BitmapDescriptor {
    val density = context.resources.displayMetrics.density
    val size = (52 * density * sizeScale).toInt().coerceAtLeast(52)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    // 官方路径内容约 15x19（居中于 24 viewport），按高度缩放填充画布
    val path = androidx.core.graphics.PathParser.createPathFromPathData(NAVIGATION_PATH_DATA)
    val scale = size * 0.88f / 19f
    path.transform(
        android.graphics.Matrix().apply {
            setScale(scale, scale)
            postTranslate(size / 2f - 12f * scale, size / 2f - 11.5f * scale)
        }
    )
    val blue = Color.rgb(0x1A, 0x73, 0xE8)
    val whiteStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = size * 0.05f
        strokeJoin = Paint.Join.ROUND
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue }
    canvas.drawPath(path, fill)
    canvas.drawPath(path, whiteStroke)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

/** 透明图标：隐藏 SDK 定位图层自带图形，仅保留相机跟随。 */
fun transparentDescriptor(context: Context): BitmapDescriptor {
    val size = 4
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.TRANSPARENT)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

/** 途经点序号标记：蓝底白字序号圆点 + 白描边（序号与 RouteBar 途经点行一致）。 */
fun waypointDescriptor(context: Context, number: Int): BitmapDescriptor {
    val density = context.resources.displayMetrics.density
    val size = (28 * density).toInt().coerceAtLeast(28)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val c = size / 2f
    canvas.drawCircle(
        c, c, c * 0.94f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    )
    canvas.drawCircle(
        c, c, c * 0.76f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x1A, 0x73, 0xE8) }
    )
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = size * 0.56f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText(
        "$number",
        c,
        c - (textPaint.descent() + textPaint.ascent()) / 2,
        textPaint
    )
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

/** 官方 Material place 图标路径数据（24x24 viewport，google/material-design-icons）。 */
private const val PLACE_PATH_DATA =
    "M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7z" +
        "M12,11.5c-1.38,0 -2.5,-1.12 -2.5,-2.5s1.12,-2.5 2.5,-2.5 2.5,1.12 2.5,2.5 -1.12,2.5 -2.5,2.5z"

/** 终点/搜索结果图钉：官方 Material place 水滴形（红底白孔 + 白描边，Google Maps 同款）。 */
fun pinDescriptor(context: Context): BitmapDescriptor {
    val density = context.resources.displayMetrics.density
    val w = (32 * density).toInt().coerceAtLeast(32)
    val h = (44 * density).toInt().coerceAtLeast(44)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    // place 内容占 viewport x[5,19] y[2,22]（14x20，居中），缩放填充画布留 8% 边距
    val path = androidx.core.graphics.PathParser.createPathFromPathData(PLACE_PATH_DATA)
    val scale = minOf(w * 0.92f / 14f, h * 0.92f / 20f)
    path.transform(
        android.graphics.Matrix().apply {
            setScale(scale, scale)
            postTranslate(w / 2f - 12f * scale, h / 2f - 12f * scale)
        }
    )
    val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xE5, 0x39, 0x35) }
    val whiteStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = w * 0.07f
        strokeJoin = Paint.Join.ROUND
    }
    // 白描边（地图对比度）→ 红填充（孔洞由环绕方向镂空）→ 孔洞填白
    canvas.drawPath(path, whiteStroke)
    canvas.drawPath(path, redPaint)
    canvas.drawCircle(
        w / 2f,
        h / 2f - 3f * scale,
        2.5f * scale * 0.9f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    )
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}
