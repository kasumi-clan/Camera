package com.godly.moonzoom

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera

/**
 * Bật các chế độ giúp ảnh nét hơn khi zoom: chống rung quang học (OIS), khử nhiễu và làm nét chất lượng cao.
 * Chỉ bật chế độ mà camera khai báo là hỗ trợ; không hỗ trợ thì bỏ qua (không gây lỗi). Trả về mô tả những gì đã bật.
 */
@OptIn(ExperimentalCamera2Interop::class)
object CameraTuning {
    fun apply(cam: Camera): String {
        val on = ArrayList<String>()
        try {
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val b = CaptureRequestOptions.Builder()
            val ois = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            if (ois != null && ois.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
                b.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
                on.add("OIS")
            }
            val nr = info.getCameraCharacteristic(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            if (nr != null && nr.contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
                b.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                on.add("NR")
            }
            val edge = info.getCameraCharacteristic(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
            if (edge != null && edge.contains(CameraMetadata.EDGE_MODE_HIGH_QUALITY)) {
                b.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
                on.add("EDGE")
            }
            if (on.isNotEmpty()) Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
        } catch (e: Throwable) {
            return "không áp dụng được: ${e.message}"
        }
        return if (on.isEmpty()) "máy không hỗ trợ" else on.joinToString("+")
    }
}
