package dev.frost819.newbv.app.entity.player.shortcut

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [pgcUnsupportedShortcutActions] 的单元测试。
 *
 * 验证番剧播放时被禁用的路由类快捷键集合：普通视频详情、UP 主页；相关视频已开放。
 */
class PgcUnsupportedShortcutActionsTest {
    @Test
    fun `contains only detail and up actions`() {
        assertThat(pgcUnsupportedShortcutActions)
            .containsExactly(
                PlayerCustomShortcutAction.OpenVideoDetail,
                PlayerCustomShortcutAction.OpenUpPage,
            )
    }

    @Test
    fun `does not contain non routing actions`() {
        assertThat(pgcUnsupportedShortcutActions)
            .doesNotContain(PlayerCustomShortcutAction.OpenComments)
        assertThat(pgcUnsupportedShortcutActions)
            .doesNotContain(PlayerCustomShortcutAction.ToggleDanmaku)
        assertThat(pgcUnsupportedShortcutActions)
            .doesNotContain(PlayerCustomShortcutAction.OpenSettings)
    }

    @Test
    fun `pgc allows related video shortcut`() {
        assertThat(pgcUnsupportedShortcutActions).doesNotContain(PlayerCustomShortcutAction.OpenRelatedVideos)
    }
}
