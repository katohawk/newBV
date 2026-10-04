package com.kuaishou.akdanmaku.ui

import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.ecs.system.DataSystem
import com.kuaishou.akdanmaku.render.SimpleRenderer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 验证有限窗口同时淘汰原始数据、活动实体和重复 id 标记。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class DanmakuPlayerRetentionTest {
    @Test
    fun `current config remains readable after pending config is consumed`() {
        // Given: 慢速弹幕的寿命超过直播淘汰默认兜底。
        val player = DanmakuPlayer(SimpleRenderer())
        val config = DanmakuConfig(rollingSpeedFactor = 0.5f).apply { rollingDurationMs = 12_000L }
        player.updateConfig(config)

        // When
        player.engine.act()

        // Then
        assertThat(player.engine.getConfig()).isNull()
        assertThat(player.getConfig()).isSameInstanceAs(config)
        assertThat(requireNotNull(player.getConfig()).rollingDurationMs).isEqualTo(24_000L)
        releaseAndAwait(player)
    }

    @Test
    fun `retention removes old raw data and allows same id after distant seek`() {
        // Given
        val player = DanmakuPlayer(SimpleRenderer())
        val system = requireNotNull(player.engine.getSystem(DataSystem::class.java))
        val original = player.updateData(listOf(item(1, 1_000), item(2, 400_000), item(3, 800_000)))
        player.engine.preAct()
        assertThat(player.engine.entities.size()).isEqualTo(1)

        // When: 请求由 preAct 在计算线程入口处理。
        system.retainData(360_000, 1_080_000)
        player.engine.preAct()

        // Then
        assertThat(rawDataCount(system)).isEqualTo(2)
        assertThat(player.engine.entities.size()).isEqualTo(0)
        assertThat(original.first().data.content).isEqualTo("dm-1")

        // 重返远端窗口时同 id 可以重新下载并创建实体。
        system.retainData(0, 360_000)
        player.updateData(listOf(item(1, 1_000)))
        player.engine.preAct()
        assertThat(rawDataCount(system)).isEqualTo(1)
        assertThat(player.engine.entities.size()).isEqualTo(1)
        releaseAndAwait(player)
    }

    @Test
    fun `queued clear preserves data appended after clear request`() {
        // Given
        val player = DanmakuPlayer(SimpleRenderer())
        val system = requireNotNull(player.engine.getSystem(DataSystem::class.java))
        player.updateData(listOf(item(1, 1_000)))

        // When
        system.clearData()
        player.updateData(listOf(item(2, 2_000)))
        player.engine.preAct()

        // Then
        assertThat(rawDataCount(system)).isEqualTo(1)
        assertThat(player.engine.entities.size()).isEqualTo(1)
        releaseAndAwait(player)
    }

    @Test
    fun `outdated append outside retained window is discarded`() {
        // Given
        val player = DanmakuPlayer(SimpleRenderer())
        val system = requireNotNull(player.engine.getSystem(DataSystem::class.java))
        system.retainData(360_000, 1_080_000)

        // When
        player.updateData(listOf(item(1, 1_000), item(2, 400_000)))
        player.engine.preAct()

        // Then
        assertThat(rawDataCount(system)).isEqualTo(1)
        releaseAndAwait(player)
    }

    private fun item(
        id: Long,
        position: Long,
    ) = DanmakuItemData(id, position, "dm-$id", DanmakuItemData.DANMAKU_MODE_ROLLING, 25, Color.WHITE)

    private fun rawDataCount(system: DataSystem): Int {
        val field = DataSystem::class.java.getDeclaredField("sortedData").apply { isAccessible = true }
        return (field.get(system) as List<*>).size
    }

    private fun releaseAndAwait(player: DanmakuPlayer) {
        player.release()
        val getter = DanmakuPlayer::class.java.getDeclaredMethod("getActionThread").apply { isAccessible = true }
        val thread = getter.invoke(player) as Thread
        thread.join(5_000)
        assertThat(thread.isAlive).isFalse()
    }
}
