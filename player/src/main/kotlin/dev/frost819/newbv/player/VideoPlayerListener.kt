package dev.frost819.newbv.player

/**
 * 播放器事件回调接口。
 *
 * 由 [AbstractVideoPlayer] 的使用者实现，接收播放器状态变化通知。
 */
interface VideoPlayerListener {
    /** 播放器发生异常 */
    fun onError(error: Exception)

    /**
     * 视频解码器无法处理当前格式（超出能力 / 初始化失败 / 格式不支持）。
     *
     * 与 [onError] 区分：调用方可据此尝试回退到其它编码或画质后重试，
     * 而非直接报错。默认交给 [onError]，避免没有回退策略的直播调用方吞掉异常。
     *
     * @param error 原始解码异常，保留诊断信息。
     */
    fun onVideoDecodeUnsupported(error: Exception) {
        onError(error)
    }

    /** 播放器准备就绪，可以开始播放 */
    fun onReady()

    /** 播放器开始播放 */
    fun onPlay()

    /** 播放器暂停 */
    fun onPause()

    /** 播放器正在缓冲 */
    fun onBuffering()

    /** 播放结束（播放到末尾） */
    fun onEnd()

    /** 后退跳跃（由遥控器触发） */
    fun onSeekBack(seekBackIncrementMs: Long)

    /** 前进跳跃（由遥控器触发） */
    fun onSeekForward(seekForwardIncrementMs: Long)
}
