package com.luxysiv.puretube.data.repository

import android.media.MediaCodecList
import com.luxysiv.puretube.error.getOrElse
import com.luxysiv.puretube.error.runAppCatchingBlocking
import java.util.Locale

/**
 * Các họ codec video mà thiết bị có decoder (phần cứng hoặc phần mềm).
 *
 * Manifest DASH tự dựng chỉ nên chứa một họ codec để đổi chất lượng không phải
 * dựng lại decoder. Nếu chọn họ có độ phân giải cao nhất mà máy không giải mã
 * được (thường gặp với AV1/VP9 trên máy cũ), track selector sẽ không có track
 * video hợp lệ và trình phát chỉ còn tiếng. Vì vậy phải lọc theo khả năng giải
 * mã thực tế của máy trước khi chọn họ codec.
 *
 * Trên JVM (unit test) [MediaCodecList] không khả dụng: [supportedFamilies]
 * trả về tập rỗng và [DashManifestBuilder] hiểu là "không biết", không lọc.
 */
internal object DeviceVideoCodecs {

    /** Rỗng = không dò được, giữ toàn bộ họ codec như trước. */
    val supportedFamilies: Set<String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        detect()
    }

    private fun detect(): Set<String> = runAppCatchingBlocking {
        val mimeTypes = HashSet<String>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder) continue
            info.supportedTypes.forEach { mimeTypes += it.lowercase(Locale.US) }
        }

        buildSet {
            if ("video/avc" in mimeTypes) add("avc")
            if ("video/x-vnd.on2.vp9" in mimeTypes) add("vp9")
            if ("video/av01" in mimeTypes) add("av01")
            if ("video/hevc" in mimeTypes) add("hevc")
        }
    }.getOrElse(emptySet())
}
