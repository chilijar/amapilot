package com.amapilot.map

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 设备朝向传感器：旋转矢量 → 方位角（真北 0~360°），静止也有航向。航向优先级
 * GPS course > 传感器 > 位移推算，不可用时 [heading] 恒 null。
 */
class HeadingSensor(
    context: Context,
    private val smoothing: Float = 0.3f
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** 最新位置（经纬度），用于地磁偏角修正；null 时跳过修正。 */
    var location: Pair<Double, Double>? = null

    private val _heading = MutableStateFlow<Float?>(null)

    /** 方位角（度，真北 0°，顺时针）。 */
    val heading: StateFlow<Float?> = _heading

    private val rotationMatrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    @Suppress("DEPRECATION")
    private val displayRotation: Int =
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.rotation

    fun start() {
        sensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, e.values)
        // 屏幕旋转补偿：把设备坐标系重映射到当前屏幕方向
        val (axisX, axisY) = when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        if (!SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remapped)) return
        SensorManager.getOrientation(remapped, orientation)
        var deg = Math.toDegrees(orientation[0].toDouble())
        // 地磁偏角修正（磁北 → 真北）
        location?.let { (lat, lng) ->
            deg += GeomagneticField(
                lat.toFloat(), lng.toFloat(), 0f, System.currentTimeMillis()
            ).declination
        }
        deg = (deg + 360.0) % 360.0
        val raw = deg.toFloat()
        val prev = _heading.value
        // 低通平滑（处理 0/360 环绕）
        _heading.value = if (prev == null) raw else {
            var delta = raw - prev
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            (prev + smoothing * delta + 360f) % 360f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
