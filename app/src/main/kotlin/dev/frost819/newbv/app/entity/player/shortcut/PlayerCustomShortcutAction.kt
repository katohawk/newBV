package dev.frost819.newbv.app.entity.player.shortcut

/**
 * 播放器自定义快捷键动作。
 *
 * 分为两类：
 * - **简单动作**：无参数，如 [ToggleDanmaku]、[PlayNext]
 * - **参数化动作**：带一个参数，如 [TogglePlaybackSpeed]，在 1 倍速和目标倍速之间切换
 */
sealed interface PlayerCustomShortcutAction {
    data object OpenSettings : PlayerCustomShortcutAction

    data object OpenRelatedVideos : PlayerCustomShortcutAction

    data object PlayPrevious : PlayerCustomShortcutAction

    data object PlayNext : PlayerCustomShortcutAction

    data object OpenVideoDetail : PlayerCustomShortcutAction

    data object OpenUpPage : PlayerCustomShortcutAction

    data object OpenComments : PlayerCustomShortcutAction

    data object OpenInteraction : PlayerCustomShortcutAction

    data object ToggleLoop : PlayerCustomShortcutAction

    data object ToggleDanmaku : PlayerCustomShortcutAction

    data object ToggleDanmakuMask : PlayerCustomShortcutAction

    data object ToggleSubtitle : PlayerCustomShortcutAction

    data object TogglePersistentBottomProgress : PlayerCustomShortcutAction

    data class TogglePlaybackSpeed(
        val speed: Float,
    ) : PlayerCustomShortcutAction
}

/**
 * 番剧（PGC）播放时不支持的快捷键动作。
 *
 * PGC 默认从剧集详情进入，不使用普通视频详情与 UP 主入口，因此隐藏对应按钮，
 * 绑定到这些动作的快捷键同样应被禁用（播放器会提示“番剧不支持”）。
 */
val pgcUnsupportedShortcutActions: Set<PlayerCustomShortcutAction> =
    setOf(
        PlayerCustomShortcutAction.OpenVideoDetail,
        PlayerCustomShortcutAction.OpenUpPage,
    )
