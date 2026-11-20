package dev.frost819.newbv.app.ui.component.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import dev.frost819.newbv.app.entity.player.VideoAspectRatio
import dev.frost819.newbv.app.entity.player.VideoListItem
import dev.frost819.newbv.app.entity.player.shortcut.PlayerCustomShortcutAction
import dev.frost819.newbv.app.entity.player.shortcut.PlayerCustomShortcutCatalog
import dev.frost819.newbv.app.entity.player.shortcut.PlayerCustomShortcutsStore
import dev.frost819.newbv.app.entity.player.shortcut.findShortcut
import dev.frost819.newbv.app.entity.player.shortcut.pgcUnsupportedShortcutActions
import dev.frost819.newbv.app.ui.action.player.DanmakuSettingAction
import dev.frost819.newbv.app.ui.action.player.MediaProfileSettingAction
import dev.frost819.newbv.app.ui.action.player.SubtitleSettingAction
import dev.frost819.newbv.app.ui.component.player.menu.MenuController
import dev.frost819.newbv.app.ui.state.player.PlayerState
import dev.frost819.newbv.app.ui.state.player.PlayerUiState
import dev.frost819.newbv.app.ui.state.player.SeekerState
import dev.frost819.newbv.app.ui.state.player.errorMessage
import dev.frost819.newbv.app.util.VideoShotImageCache
import dev.frost819.newbv.biliapi.entity.video.Subtitle
import dev.frost819.newbv.core.theme.BVTheme
import dev.frost819.newbv.core.theme.ThemeMode
import dev.frost819.newbv.data.datastore.Prefs
import dev.frost819.newbv.data.datastore.ScreenMaskConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SEEK_STEP_MS = 10_000L
private const val SEEK_ACCELERATION_MS = 5_000L
private const val SEEK_TAP_INTERVAL_MS = 200L
private const val SEEK_HOLD_INTERVAL_MS = 150L
private const val SEEK_STEPS_PER_LEVEL = 5
private const val SEEK_MAX_HOLD_STEP_MS = 60_000L

/**
 * 播放器根控制器。
 *
 * 管理所有覆盖层的可见性和焦点路由，处理 D-pad 按键事件、seek 加速、
 * 自定义快捷键、以及视频列表/菜单/相关信息控制器之间的协调。
 *
 * 布局层次（从底到顶）：
 * 1. content() — 视频画面 + 弹幕层
 * 2. BottomSubtitle — 字幕文本
 * 3. SkipTips — 跳转提示
 * 4. PlayStateTips — 播放状态提示
 * 5. RelatedVideosController — 相关视频
 * 6. ControllerVideoInfo — 信息栏 + 进度条 + 按钮
 * 7. VideoListController — 分集列表
 * 8. MenuController — 设置菜单
 *
 * @param onVideoFocused 选集面板父视频获得焦点时按需补齐分P。
 * @param onRetryRelatedVideos 重试剧集相关推荐。
 */
@Composable
@Suppress("LongParameterList", "CyclomaticComplexMethod")
fun VideoPlayerController(
    modifier: Modifier = Modifier,
    isPgc: Boolean,
    isLooping: Boolean,
    videoShotCache: VideoShotImageCache,
    uiState: PlayerUiState,
    seekerState: State<SeekerState>,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onExit: () -> Unit,
    onGoTime: (time: Long) -> Unit,
    onBackToStart: () -> Unit,
    onCancelSkipToNextEp: () -> Unit,
    onPlayNewVideo: (VideoListItem) -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onToggleLoop: () -> Unit,
    onToggleSubtitle: () -> Unit,
    onGoToUpPage: () -> Unit,
    onGoToVideoDetail: () -> Unit,
    onShowInteraction: () -> Unit,
    onShowComments: () -> Unit,
    onMediaProfileSettingChange: (MediaProfileSettingAction) -> Unit,
    onAspectRatioChange: (VideoAspectRatio) -> Unit,
    onPlaySpeedChange: (Float) -> Unit,
    onDanmakuSettingChange: (DanmakuSettingAction) -> Unit,
    onSubtitleChange: (Subtitle) -> Unit,
    onSubtitleSettingChange: (SubtitleSettingAction) -> Unit,
    onRelatedVideoClicked: (dev.frost819.newbv.app.ui.component.videocard.VideoCardData) -> Unit,
    onToggleDanmaku: () -> Unit,
    onShowShortcutTip: (String) -> Unit,
    screenMask: ScreenMaskConfig,
    isMaskEditing: Boolean,
    onScreenMaskChange: (ScreenMaskConfig) -> Unit,
    onEditScreenMask: () -> Unit,
    onVideoFocused: (Long) -> Unit = {},
    onRetryRelatedVideos: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val controllerFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 覆盖层可见性
    var showListController by remember { mutableStateOf(false) }
    var showMenuController by remember { mutableStateOf(false) }
    var showInfoSeekController by remember { mutableStateOf(false) }
    var showRelatedVideosController by remember(uiState.aid) { mutableStateOf(false) }
    val showClickableControllers by remember(uiState.aid) {
        derivedStateOf {
            showListController ||
                showMenuController ||
                showInfoSeekController ||
                showRelatedVideosController
        }
    }

    LaunchedEffect(showClickableControllers, isMaskEditing) {
        if (!showClickableControllers && !isMaskEditing) {
            controllerFocus.requestFocus()
        }
    }

    // Seek 加速状态
    var goTime by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekChangeCount by remember { mutableLongStateOf(0L) }
    var lastSeekChangeTime by remember { mutableLongStateOf(0L) }
    var seekCountdown: Job? by remember { mutableStateOf(null) }

    var heldSeekKey by remember { mutableStateOf<Key?>(null) }
    var holdAdvanced by remember { mutableStateOf(false) }
    var holdJob by remember { mutableStateOf<Job?>(null) }
    val longPressTimeoutMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val currentPlayerState by rememberUpdatedState(uiState.playerState)

    fun stopHoldingSeek() {
        holdJob?.cancel()
        heldSeekKey = null
        holdAdvanced = false
    }

    // 换分 P 或窗口失焦可能收不到抬键，旧的预览和计时器不能带到下一段播放。
    DisposableEffect(uiState.aid, uiState.cid, LocalWindowInfo.current.isWindowFocused) {
        onDispose {
            stopHoldingSeek()
            seekCountdown?.cancel()
            isSeeking = false
            lastSeekChangeTime = 0L
        }
    }

    // 常显进度条
    var showPersistentSeek by remember { mutableStateOf(Prefs.showPersistentSeek) }

    // 手势状态
    val gestureTipState = rememberGestureTipState()
    var currentBrightness by remember { mutableFloatStateOf(-1f) }

    fun advanceSeek(deltaMs: Long) {
        val start = if (isSeeking) goTime else seekerState.value.currentTime
        goTime = (start + deltaMs).coerceIn(0L, seekerState.value.totalDuration.coerceAtLeast(0L))
        isSeeking = true
    }

    /** 隐藏信息栏 + 进度条控制器。 */
    fun hideController() {
        showInfoSeekController = false
    }

    // 控制器自动隐藏：可见时 5 秒无交互自动收起，输入入口调用 onInteraction 续期
    val onInteraction =
        rememberControllerAutoHide(
            visible = showInfoSeekController && heldSeekKey == null,
            onHide = { hideController() },
        )

    fun onSeekGoTime() {
        stopHoldingSeek()
        seekCountdown?.cancel()
        onGoTime(goTime)
        if (currentPlayerState != PlayerState.Playing) {
            onPlay()
        }
        isSeeking = false
        hideController()
    }

    fun startSeekCountdown(restart: Boolean = true) {
        if (!restart && seekCountdown?.isActive == true) {
            return
        }
        seekCountdown?.cancel()
        seekCountdown =
            scope.launch {
                delay(1000)
                if (heldSeekKey == null) {
                    onSeekGoTime()
                }
            }
    }

    fun onSeekKeyDown(event: KeyEvent) {
        if (event.nativeKeyEvent.repeatCount != 0) {
            return
        }
        val direction = if (event.key == Key.DirectionLeft || event.key == Key.MediaRewind) -1 else 1
        val eventTime = event.nativeKeyEvent.eventTime
        val rapidTap = isSeeking && eventTime - lastSeekChangeTime in 0 until SEEK_TAP_INTERVAL_MS
        seekChangeCount = if (rapidTap) seekChangeCount + 1 else 0
        lastSeekChangeTime = eventTime
        val step = SEEK_STEP_MS + seekChangeCount / SEEK_STEPS_PER_LEVEL * SEEK_ACCELERATION_MS
        showInfoSeekController = true
        advanceSeek(direction * step)

        heldSeekKey = event.key
        holdJob =
            scope.launch {
                delay(longPressTimeoutMs)
                holdAdvanced = true
                var ticks = 0L
                // 遥控器上报重复事件的频率各异，按住期间由播放器统一推进预览。
                while (true) {
                    val level = 1 + ticks / SEEK_STEPS_PER_LEVEL
                    val holdStep = (SEEK_STEP_MS + level * SEEK_ACCELERATION_MS).coerceAtMost(SEEK_MAX_HOLD_STEP_MS)
                    advanceSeek(direction * holdStep)
                    if (goTime == 0L || goTime == seekerState.value.totalDuration) {
                        break
                    }
                    ticks++
                    delay(SEEK_HOLD_INTERVAL_MS)
                }
            }
    }

    fun onSeekToPosition(positionMs: Long) {
        isSeeking = true
        goTime = positionMs.coerceIn(0L, seekerState.value.totalDuration)
        stopHoldingSeek()
        startSeekCountdown()
    }

    fun closeAllControllers() {
        hideController()
        showListController = false
        showMenuController = false
        showRelatedVideosController = false
    }

    fun finishPendingSeek() {
        stopHoldingSeek()
        seekCountdown?.cancel()
        if (isSeeking) onGoTime(goTime)
        isSeeking = false
    }

    fun openRelatedVideos() {
        finishPendingSeek()
        closeAllControllers()
        showRelatedVideosController = true
    }

    /**
     * 显示自定义快捷键触发提示浮层。
     *
     * 复用 [PlayerTip] 组件显示动作名称，定时结束后自动消失。
     * 快捷键提示始终显示，新的提示会覆盖旧提示。
     *
     * 对于开关类/参数类动作，[status] 会追加在动作名称后面（如"字幕：开"）。
     */
    fun showShortcutTip(
        action: PlayerCustomShortcutAction,
        status: String? = null,
    ) {
        val name = PlayerCustomShortcutCatalog.getActionDisplayName(action)
        val tip = if (status != null) "$name：$status" else name
        onShowShortcutTip(tip)
    }

    fun executeCustomShortcut(action: PlayerCustomShortcutAction) {
        // PGC 使用剧集详情；普通视频详情和 UP 主页快捷键不适用
        if (isPgc && action in pgcUnsupportedShortcutActions) {
            showShortcutTip(action, "番剧不支持")
            return
        }
        var status: String? = null
        when (action) {
            PlayerCustomShortcutAction.OpenSettings -> showMenuController = true
            PlayerCustomShortcutAction.OpenRelatedVideos -> openRelatedVideos()
            PlayerCustomShortcutAction.PlayPrevious -> onPlayPrevious()
            PlayerCustomShortcutAction.PlayNext -> onPlayNext()
            PlayerCustomShortcutAction.OpenVideoDetail -> onGoToVideoDetail()
            PlayerCustomShortcutAction.OpenUpPage -> onGoToUpPage()
            PlayerCustomShortcutAction.OpenComments -> onShowComments()
            PlayerCustomShortcutAction.OpenInteraction -> onShowInteraction()
            PlayerCustomShortcutAction.ToggleLoop -> {
                status = if (!isLooping) "开" else "关"
                onToggleLoop()
            }
            PlayerCustomShortcutAction.ToggleDanmaku -> {
                status = if (uiState.danmakuState.enabledTypes.isEmpty()) "开" else "关"
                onToggleDanmaku()
            }
            PlayerCustomShortcutAction.ToggleSubtitle -> {
                status = if (uiState.subtitleId == -1L) "开" else "关"
                onToggleSubtitle()
            }
            PlayerCustomShortcutAction.TogglePersistentBottomProgress -> {
                showPersistentSeek = !showPersistentSeek
                Prefs.showPersistentSeek = showPersistentSeek
                status = if (showPersistentSeek) "开" else "关"
            }

            is PlayerCustomShortcutAction.TogglePlaybackSpeed -> {
                val targetSpeed = if (uiState.playSpeed == action.speed) 1f else action.speed
                status = "${targetSpeed}x"
                onPlaySpeedChange(targetSpeed)
            }

            is PlayerCustomShortcutAction.ToggleDanmakuMask -> {
                val newMaskEnabled = !uiState.danmakuState.maskEnabled
                status = if (newMaskEnabled) "开" else "关"
                onDanmakuSettingChange(
                    DanmakuSettingAction.SetMaskEnabled(newMaskEnabled),
                )
            }
        }
        showShortcutTip(action, status)
    }

    fun handleCustomShortcut(event: KeyEvent): Boolean {
        if (showClickableControllers) return false
        val shortcut =
            findShortcut(
                keyCode = event.nativeKeyEvent.keyCode,
                bindings = PlayerCustomShortcutsStore.getByKey(),
            ) ?: return false
        if (event.type == KeyEventType.KeyUp) return true
        if (event.type != KeyEventType.KeyDown) return false
        if (event.nativeKeyEvent.repeatCount != 0) return true
        executeCustomShortcut(shortcut.action)
        return true
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val confirmKeys = listOf(Key.DirectionCenter, Key.Enter, Key.Spacebar)

        if (event.key == heldSeekKey) {
            if (event.type == KeyEventType.KeyUp) {
                val commitNow = holdAdvanced
                stopHoldingSeek()
                if (event.nativeKeyEvent.isCanceled) {
                    seekCountdown?.cancel()
                    isSeeking = false
                } else if (commitNow) {
                    onSeekGoTime()
                } else {
                    startSeekCountdown(restart = false)
                }
            }
            return true
        }
        if (heldSeekKey != null && event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
            stopHoldingSeek()
            startSeekCountdown(restart = false)
        }

        // 非 confirm 键的 KeyUp 事件消费掉
        if (event.type == KeyEventType.KeyUp && event.key !in confirmKeys) {
            return true
        }

        // 自定义快捷键
        if (handleCustomShortcut(event)) return true

        // 播放快捷键（KeyUp 已被顶层过滤，此处均为 KeyDown）
        when (event.key) {
            Key.Back -> {
                if (showClickableControllers) {
                    closeAllControllers()
                    return true
                }
                onExit()
                return true
            }

            Key.Menu, Key(763) -> {
                if (showMenuController || showRelatedVideosController) return false
                if (event.nativeKeyEvent.repeatCount == 0) {
                    finishPendingSeek()
                    hideController()
                    showListController = !showListController
                }
                return true
            }

            Key.MediaPlayPause -> {
                onPlay()
                return true
            }

            Key.MediaPlay -> {
                if (uiState.playerState != PlayerState.Playing) onPlay()
                return true
            }

            Key.MediaPause -> {
                if (uiState.playerState == PlayerState.Playing) onPause()
                return true
            }
        }

        // 覆盖层未打开时的按键（KeyUp 已被顶层过滤，此处均为 KeyDown）
        if (!showClickableControllers) {
            when (event.key) {
                in confirmKeys -> {
                    if (event.type == KeyEventType.KeyDown) {
                        if (event.nativeKeyEvent.isLongPress) {
                            showMenuController = true
                        }
                        return true
                    } else {
                        if (uiState.showBackToStart) {
                            onBackToStart()
                        } else {
                            onPlay()
                        }
                        return true
                    }
                }

                Key.DirectionUp -> {
                    if (event.nativeKeyEvent.repeatCount == 0) {
                        openRelatedVideos()
                    }
                    return true
                }

                Key.DirectionDown -> {
                    if (event.nativeKeyEvent.repeatCount == 0) {
                        showInfoSeekController = true
                    }
                    return true
                }

                Key.DirectionLeft, Key.MediaRewind -> {
                    if (uiState.showSkipToNextEp) {
                        onCancelSkipToNextEp()
                        return true
                    }
                    onSeekKeyDown(event)
                    return true
                }

                Key.DirectionRight, Key.MediaFastForward -> {
                    onSeekKeyDown(event)
                    return true
                }
            }
        }

        return false
    }

    Box(
        modifier =
            modifier
                .background(Color.Black)
                .focusRequester(controllerFocus)
                .onPreviewKeyEvent { event ->
                    onInteraction()
                    handleKeyEvent(event)
                }.focusable()
                .playerGestures(
                    totalDuration = { seekerState.value.totalDuration },
                    controllerVisible = { showInfoSeekController },
                    callbacks =
                        PlayerGestureCallbacks(
                            onSingleTap = {
                                if (!showClickableControllers) {
                                    if (showInfoSeekController) {
                                        hideController()
                                    } else {
                                        showInfoSeekController = true
                                    }
                                } else {
                                    closeAllControllers()
                                }
                            },
                            onDoubleTap = { onPlay() },
                            onSeekDelta = { deltaMs ->
                                stopHoldingSeek()
                                advanceSeek(deltaMs)
                                showInfoSeekController = true
                                startSeekCountdown()
                            },
                            onSeekCommit = { },
                            onBrightnessChange = { deltaY ->
                                val activity = context as? android.app.Activity
                                if (activity != null) {
                                    currentBrightness = adjustBrightness(activity, deltaY, currentBrightness)
                                    gestureTipState.value =
                                        GestureTipState(
                                            isActive = true,
                                            type = GestureTipType.Brightness,
                                            value = currentBrightness,
                                        )
                                }
                            },
                            onVolumeChange = { deltaY ->
                                val audioManager =
                                    context.getSystemService(android.content.Context.AUDIO_SERVICE)
                                        as? android.media.AudioManager
                                if (audioManager != null) {
                                    val volumePercent = adjustVolume(audioManager, deltaY)
                                    gestureTipState.value =
                                        GestureTipState(
                                            isActive = true,
                                            type = GestureTipType.Volume,
                                            value = volumePercent.toFloat(),
                                        )
                                }
                            },
                            onUserInteraction = onInteraction,
                        ),
                    gestureTipState = gestureTipState,
                ),
    ) {
        // 播放器画面与覆盖层始终基于黑色背景，固定使用深色主题，
        // 避免浅色应用下默认取色变成深色文字叠在黑底上不可见。
        // backgroundColor = Black：BVTheme 内部的 TvSurface 默认会用 colorScheme.surface
        // （深灰 #222222）铺满整屏，导致 4:3 视频在 16:9 屏上左右留白呈灰色。
        BVTheme(
            themeMode = ThemeMode.Dark,
            density = LocalDensity.current.density,
            backgroundColor = Color.Black,
        ) {
            // 视频画面 + 弹幕层
            content()

            // 调试信息
            if (Prefs.showPlayerDebugInfo) {
                Box(
                    modifier =
                        Modifier
                            .align(androidx.compose.ui.Alignment.TopStart)
                            .padding(8.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(Color.Black.copy(alpha = 0.5f)),
                ) {
                    Text(
                        modifier = Modifier.padding(8.dp),
                        text = seekerState.value.debugInfo,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // 常显进度条
            if (showPersistentSeek && !showInfoSeekController) {
                VideoProgressSeek(
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                    duration = seekerState.value.totalDuration,
                    position = seekerState.value.currentTime,
                    bufferedPercentage = seekerState.value.bufferedPercentage,
                    isPersistentSeek = true,
                )
            }

            // 字幕
            if (uiState.subtitleId != -1L) {
                BottomSubtitle(
                    subtitleData = uiState.subtitleData,
                    currentTime = seekerState.value.currentTime,
                    fontSize =
                        androidx.compose.ui.unit.TextUnit(
                            uiState.subtitleState.fontSize.toFloat(),
                            androidx.compose.ui.unit.TextUnitType.Sp,
                        ),
                    opacity = uiState.subtitleState.opacity,
                    padding =
                        androidx.compose.ui.unit
                            .Dp(uiState.subtitleState.bottomPadding.toFloat()),
                )
            }

            // 跳转提示
            SkipTips(
                showBackToStart = uiState.showBackToStart,
                showSkipToNextEp = uiState.showSkipToNextEp,
                showPreviewTip = uiState.showPreviewTip,
                shortcutTipText = uiState.shortcutTipText,
            )

            // 播放状态提示
            PlayStateTips(
                isPlaying = uiState.playerState == PlayerState.Playing,
                isBuffering = uiState.isBuffering,
                errorMessage = uiState.playerState.errorMessage,
            )

            // 手势提示（亮度/音量/倍速反馈）
            GestureTip(
                state = gestureTipState.value,
                modifier = Modifier.align(Alignment.Center),
            )

            // 相关视频
            key(uiState.aid) {
                RelatedVideosController(
                    show = showRelatedVideosController,
                    relatedVideos = uiState.relatedVideos,
                    isLoading = uiState.relatedLoading,
                    isError = uiState.relatedError,
                    onRetry = onRetryRelatedVideos,
                    onVideoClicked = { video ->
                        closeAllControllers()
                        onRelatedVideoClicked(video)
                    },
                    onDismiss = ::closeAllControllers,
                    onPlayerKeyEvent = ::handleKeyEvent,
                )
            }

            // 信息栏 + 进度条 + 按钮
            ControllerVideoInfo(
                show = showInfoSeekController,
                isSeeking = isSeeking,
                goTime = goTime,
                seekerState = seekerState.value,
                title = uiState.title,
                clock = uiState.clock,
                onlineWatching = uiState.onlineWatching,
                videoShot = uiState.videoShot,
                videoShotCache = videoShotCache,
                isPgc = isPgc,
                danmakuEnabled = uiState.danmakuState.enabledTypes.isNotEmpty(),
                isLooping = isLooping,
                onDismiss = ::hideController,
                onSeekKeyDown = ::onSeekKeyDown,
                onSeekGoTime = ::onSeekGoTime,
                onSeekToPosition = ::onSeekToPosition,
                onPlayPause = { onPlay() },
                onDanmakuSwitchChange = { onToggleDanmaku() },
                onShowSettings = { showMenuController = true },
                onShowRelatedVideos = ::openRelatedVideos,
                onGoToVideoInfo = onGoToVideoDetail,
                onToggleLoop = { onToggleLoop() },
                onGoToUpPage = onGoToUpPage,
                onShowInteraction = onShowInteraction,
                onShowComments = onShowComments,
            )

            // 分集列表
            // 切换视频后保持列表展开，方便连续选集；按返回键收起
            VideoListController(
                onVideoFocused = onVideoFocused,
                show = showListController,
                currentCid = uiState.cid,
                videoList = uiState.videoList,
                onPlayNewVideo = onPlayNewVideo,
            )

            // 设置菜单
            MenuController(
                show = showMenuController,
                uiState = uiState,
                onResolutionChange = { onMediaProfileSettingChange(MediaProfileSettingAction.SetQuality(it)) },
                onCodecChange = { onMediaProfileSettingChange(MediaProfileSettingAction.SetVideoCodec(it)) },
                onAspectRatioChange = onAspectRatioChange,
                onPlaySpeedChange = onPlaySpeedChange,
                onAudioChange = { onMediaProfileSettingChange(MediaProfileSettingAction.SetAudio(it)) },
                onDanmakuSwitchChange = { types ->
                    // data DanmakuType → danmaku entity DanmakuType
                    val entityTypes =
                        types.mapNotNull {
                            runCatching { dev.frost819.newbv.danmaku.entity.DanmakuType.entries[it.ordinal] }
                                .getOrNull()
                        }
                    onDanmakuSettingChange(DanmakuSettingAction.SetEnabledTypes(entityTypes))
                },
                onDanmakuSizeChange = { onDanmakuSettingChange(DanmakuSettingAction.SetScale(it)) },
                onDanmakuOpacityChange = { onDanmakuSettingChange(DanmakuSettingAction.SetOpacity(it)) },
                onDanmakuSpeedFactorChange = { onDanmakuSettingChange(DanmakuSettingAction.SetSpeedFactor(it)) },
                onDanmakuAreaChange = { onDanmakuSettingChange(DanmakuSettingAction.SetArea(it)) },
                onDanmakuMaskChange = { onDanmakuSettingChange(DanmakuSettingAction.SetMaskEnabled(it)) },
                onDanmakuBlockColorfulEnabledChange = {
                    onDanmakuSettingChange(DanmakuSettingAction.SetBlockColorfulEnabled(it))
                },
                onDanmakuBlockRepeatEnabledChange = {
                    onDanmakuSettingChange(DanmakuSettingAction.SetBlockRepeatEnabled(it))
                },
                onDanmakuBlockByUserFilterEnabledChange = {
                    onDanmakuSettingChange(DanmakuSettingAction.SetBlockByUserFilterEnabled(it))
                },
                onDanmakuAiCloudLevelChange = { onDanmakuSettingChange(DanmakuSettingAction.SetAiCloudLevel(it)) },
                onSubtitleChange = { subtitle -> onSubtitleChange(subtitle) },
                onSubtitleSizeChange = { onSubtitleSettingChange(SubtitleSettingAction.SetFontSize(it)) },
                onSubtitleBackgroundOpacityChange = { onSubtitleSettingChange(SubtitleSettingAction.SetOpacity(it)) },
                onSubtitleBottomPadding = { onSubtitleSettingChange(SubtitleSettingAction.SetBottomPadding(it)) },
                screenMask = screenMask,
                onScreenMaskChange = onScreenMaskChange,
                onEditScreenMask = {
                    seekCountdown?.cancel()
                    isSeeking = false
                    closeAllControllers()
                    onEditScreenMask()
                },
            )
        }
    }
}
