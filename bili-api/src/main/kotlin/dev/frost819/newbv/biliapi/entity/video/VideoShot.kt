package dev.frost819.newbv.biliapi.entity.video

import dev.frost819.newbv.biliapi.http.BiliHttpApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * Seek 预览的时间索引和 Sprite URL，不持有压缩图片。
 * @property times 每一格对应的秒级时间。
 * @property imageUrls Sprite 图片地址，按索引在预览时加载。
 * @property imageCountX 每张 Sprite 的列数。
 * @property imageCountY 每张 Sprite 的行数。
 * @property imageWidth 单格原始宽度。
 * @property imageHeight 单格原始高度。
 */
data class VideoShot(
    val times: List<UShort>,
    val imageUrls: List<String>,
    val imageCountX: Int,
    val imageCountY: Int,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    /** 将接口响应转换为可按需加载图片的预览索引。 */
    companion object {
        /** 仅下载时间索引；取消上抛，缺失或损坏的索引不影响视频播放。 */
        suspend fun fromVideoShot(videoShot: dev.frost819.newbv.biliapi.http.entity.video.VideoShot): VideoShot? =
            withContext(Dispatchers.IO) {
                if (videoShot.image.isEmpty() || videoShot.imgXLen <= 0 || videoShot.imgYLen <= 0) {
                    return@withContext null
                }
                try {
                    val timeBinary = BiliHttpApi.download(videoShot.pvData ?: return@withContext null)
                    if (timeBinary.size < 4 || timeBinary.size % 2 != 0) return@withContext null
                    val times =
                        DataInputStream(ByteArrayInputStream(timeBinary)).use { input ->
                            input.readUnsignedShort() // pvdata 首项是格式头，不对应预览帧。
                            List((timeBinary.size / 2) - 1) { input.readUnsignedShort().toUShort() }
                        }
                    if (times.zipWithNext().any { (a, b) -> a > b }) return@withContext null
                    VideoShot(
                        times = times,
                        imageUrls = videoShot.image,
                        imageCountX = videoShot.imgXLen,
                        imageCountY = videoShot.imgYLen,
                        imageWidth = videoShot.imgXSize,
                        imageHeight = videoShot.imgYSize,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            }
    }
}
