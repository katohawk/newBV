package dev.frost819.newbv.player.impl.exo

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.upstream.Allocation
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** 验证低堆预算下缓冲停止、重新加载及释放，防止高清视频耗尽进程堆。 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackLoadControlTest {
    @Test
    fun bufferBudget_stopsLoadingBeforeTimeTarget_andResumesAfterConsumption() {
        // Given: 覆盖小堆、芝杜 128 MiB 堆和大堆设备，实际分配 Media3 缓冲块。
        for ((heapMiB, budgetMiB) in listOf(64 to 16, 128 to 32, 384 to 64, 512 to 64)) {
            val loadControl = createPlaybackLoadControl(heapMiB * 1024L * 1024)
            val playerId = PlayerId.UNSET
            // 新版按媒体 URI 区分本地与网络缓冲策略，使用有效的网络媒体时间线。
            val timeline =
                SinglePeriodTimeline(
                    120_000_000L,
                    true,
                    false,
                    false,
                    null,
                    MediaItem.fromUri("https://example.com/video.mp4"),
                )
            val parameters =
                LoadControl.Parameters(
                    playerId,
                    timeline,
                    MediaPeriodId(timeline.getUidOfPeriod(0)),
                    0L,
                    100_000L,
                    2f,
                    true,
                    true,
                    C.TIME_UNSET,
                    C.TIME_UNSET,
                )
            val allocator = loadControl.getAllocator(playerId)
            val allocations = mutableListOf<Allocation>()
            loadControl.onPrepared(playerId)
            loadControl.onTracksSelected(parameters, TrackGroupArray.EMPTY, emptyArray())
            try {
                assertThat(loadControl.shouldContinueLoading(parameters)).isTrue()
                assertThat(loadControl.shouldStartPlayback(parameters)).isFalse()

                // When: 缓冲不足启动时长，但已到字节预算，必须停止加载并允许播放。
                repeat(budgetMiB * 1024 * 1024 / allocator.individualAllocationLength) {
                    allocations += allocator.allocate()
                }
                assertThat(loadControl.shouldContinueLoading(parameters)).isFalse()
                assertThat(loadControl.shouldStartPlayback(parameters)).isTrue()

                // Then: 消耗后恢复加载，退出播放后释放缓冲。
                allocator.release(allocations.removeAt(allocations.lastIndex))
                assertThat(loadControl.shouldContinueLoading(parameters)).isTrue()
            } finally {
                allocations.forEach { allocator.release(it) }
                // 新版分配器绑定 PlayerId，onReleased 后已不能查询该播放器的计数。
                val bytesAfterBufferRelease = allocator.totalBytesAllocated
                loadControl.onReleased(playerId)
                assertThat(bytesAfterBufferRelease).isEqualTo(0)
            }
        }
    }
}
