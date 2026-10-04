package com.kuaishou.akdanmaku.ui

import com.google.common.truth.Truth.assertThat
import com.kuaishou.akdanmaku.render.SimpleRenderer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 验证无绘制许可时仍能释放，以及缓存线程确实收到终止请求。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class DanmakuPlayerLifecycleTest {
    @Test
    fun `release completes without a view draw and repeated release is safe`() {
        // Given: 没有 onDraw 会归还信号量，释放仍须让计算线程继续处理。
        val player = DanmakuPlayer(SimpleRenderer())
        player.bindView(DanmakuView(RuntimeEnvironment.getApplication()))
        player.start()

        // When
        player.release()
        player.release()
        awaitCacheRelease(player)

        // Then
        assertThat(player.isReleased).isTrue()
        assertThat(player.engine.systems.size()).isEqualTo(0)
        assertThat(player.updateData(emptyList())).isEmpty()
    }

    @Test
    fun `release terminates an initialized cache worker`() {
        // Given: 发送测量任务，确保缓存线程实际初始化。
        val player = DanmakuPlayer(SimpleRenderer())
        val cacheManager = player.engine.context.cacheManager
        cacheManager.requestMeasure(
            player.obtainItem(
                com.kuaishou.akdanmaku.data
                    .DanmakuItemData(1, 0, "test", 1, 25, android.graphics.Color.WHITE),
            ),
            player.engine.context.displayer,
            player.engine.context.config,
        )
        val cacheGetter = cacheManager.javaClass.getDeclaredMethod("getCacheThread").apply { isAccessible = true }
        val cacheThread = cacheGetter.invoke(cacheManager) as Thread

        // When
        player.release()
        awaitCacheRelease(player)
        cacheThread.join(5_000)

        // Then: RELEASE 消息清空缓存池并退出工作线程。
        assertThat(cacheManager.isReleased).isTrue()
        assertThat(cacheThread.isAlive).isFalse()
    }

    private fun awaitCacheRelease(player: DanmakuPlayer) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!player.engine.context.cacheManager.isReleased && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertThat(player.engine.context.cacheManager.isReleased).isTrue()
        val getter = DanmakuPlayer::class.java.getDeclaredMethod("getActionThread").apply { isAccessible = true }
        val thread = getter.invoke(player) as Thread
        thread.join(5_000)
        assertThat(thread.isAlive).isFalse()
    }
}
