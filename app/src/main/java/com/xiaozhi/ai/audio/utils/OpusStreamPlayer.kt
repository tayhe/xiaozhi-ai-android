package com.xiaozhi.ai.audio.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class OpusStreamPlayer(
    private val sampleRate: Int,
    private val channels: Int,
    frameSizeMs: Int,
    private val context: Context? = null
) {
    companion object {
        private const val TAG = "OpusStreamPlayer"
    }

    private var audioTrack: AudioTrack
    private val playerScope = CoroutineScope(Dispatchers.IO + Job())
    private var isPlaying = false
    private var playbackJob: Job? = null
    
    // 音频焦点管理
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    // 播放进度与帧计数追踪
    @Volatile
    private var totalWrittenFrames = 0L
    @Volatile
    private var startHeadPosition = 0L

    init {
        val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2 // Increase buffer size

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            
        // 初始化音频焦点管理
        context?.let {
            audioManager = it.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            setupAudioFocus()
        }
    }
    
    private fun setupAudioFocus() {
        audioManager?.let { am ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { focusChange ->
                        handleAudioFocusChange(focusChange)
                    }
                    .build()
            }
        }
    }
    
    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                Log.d(TAG, "获得音频焦点")
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                Log.d(TAG, "失去音频焦点")
                stop()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "音频焦点被降低")
            }
        }
    }
    
    private fun requestAudioFocus(): Boolean {
        audioManager?.let { am ->
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.requestAudioFocus(it) } ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    { focusChange -> handleAudioFocusChange(focusChange) },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
            
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.d(TAG, "请求音频焦点结果: $hasAudioFocus")
            return hasAudioFocus
        }
        return true // 如果没有context，假设有焦点
    }
    
    private fun abandonAudioFocus() {
        if (hasAudioFocus) {
            audioManager?.let { am ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                } else {
                    @Suppress("DEPRECATION")
                    am.abandonAudioFocus { focusChange -> handleAudioFocusChange(focusChange) }
                }
            }
            hasAudioFocus = false
            Log.d(TAG, "释放音频焦点")
        }
    }

    /**
     * 确保音频通过底部外放扬声器输出，防止通话模式下被系统误路由到听筒
     */
    fun ensureSpeakerphone() {
        audioManager?.let { am ->
            try {
                am.isSpeakerphoneOn = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val devices = am.availableCommunicationDevices
                    val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speaker != null) {
                        am.setCommunicationDevice(speaker)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "设置扬声器失败: ${e.message}")
            }
        }
    }

    /**
     * 启动播放准备（无参）
     */
    fun start() {
        if (isPlaying) {
            return
        }
        requestAudioFocus()
        ensureSpeakerphone()
        
        isPlaying = true
        if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
            try {
                audioTrack.play()
                startHeadPosition = audioTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                totalWrittenFrames = 0L
                Log.d(TAG, "AudioTrack开始播放, startHeadPosition: $startHeadPosition")
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack启动播放异常", e)
                isPlaying = false
            }
        } else {
            Log.e(TAG, "AudioTrack未初始化，状态: ${audioTrack.state}")
            isPlaying = false
        }
    }

    /**
     * 写入解码后的 PCM 数据到 AudioTrack
     */
    fun write(pcmData: ByteArray): Int {
        if (!isPlaying) {
            start()
        }
        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            return -1
        }
        if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
            try {
                audioTrack.play()
            } catch (e: Exception) {
                Log.e(TAG, "恢复AudioTrack播放失败", e)
            }
        }
        val bytesWritten = audioTrack.write(pcmData, 0, pcmData.size)
        if (bytesWritten > 0) {
            val frames = bytesWritten / (channels * 2)
            totalWrittenFrames += frames
        } else {
            Log.e(TAG, "AudioTrack写入失败: $bytesWritten")
        }
        return bytesWritten
    }

    /**
     * 支持 Flow 流式播放（兼容示例与已有调用）
     */
    fun start(pcmFlow: Flow<ByteArray?>) {
        start()
        playbackJob?.cancel()
        playbackJob = playerScope.launch {
            try {
                Log.d(TAG, "开始收集PCM数据流")
                pcmFlow.collect { pcmData ->
                    if (isPlaying && pcmData != null) {
                        write(pcmData)
                    }
                }
                Log.d(TAG, "PCM数据流收集完成")
            } catch (e: Exception) {
                Log.e(TAG, "播放音频流时出错", e)
            } finally {
                Log.d(TAG, "播放任务结束")
            }
        }
    }

    /**
     * 立即停止播放并清空硬件缓冲（用于用户打断、挂断等立即静音场景）
     */
    fun stop() {
        if (isPlaying) {
            isPlaying = false
            playbackJob?.cancel()
            playbackJob = null
            
            if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                try {
                    audioTrack.pause()
                    audioTrack.flush()
                    Log.d(TAG, "AudioTrack已暂停并清空缓冲")
                } catch (e: Exception) {
                    Log.e(TAG, "停止AudioTrack失败", e)
                }
            }
            totalWrittenFrames = 0L
            abandonAudioFocus()
        }
    }

    fun release() {
        stop()
        audioTrack.release()
        playerScope.cancel()
    }

    /**
     * 等待所有已写入 AudioTrack 的音频帧完全播放完毕
     * @param timeoutMs 最大超时时间，防止底层驱动卡死
     */
    suspend fun waitForPlaybackCompletion(timeoutMs: Long = 30000) {
        if (!isPlaying || totalWrittenFrames == 0L) {
            return
        }
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "开始等待音频播放完成: targetTotalFrames=$totalWrittenFrames, startHeadPos=$startHeadPosition")
        
        while (isPlaying) {
            val currentHeadPos = audioTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            val playedFrames = if (currentHeadPos >= startHeadPosition) {
                currentHeadPos - startHeadPosition
            } else {
                // 说明底层驱动 reset 回 0
                currentHeadPos
            }
            
            if (playedFrames >= totalWrittenFrames) {
                Log.d(TAG, "所有音频帧播放完毕: playedFrames=$playedFrames, targetTotalFrames=$totalWrittenFrames")
                break
            }
            
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                Log.w(TAG, "等待音频播放超时: playedFrames=$playedFrames, targetTotalFrames=$totalWrittenFrames")
                break
            }
            
            delay(30)
        }
    }

    fun isCurrentlyPlaying(): Boolean {
        return isPlaying && audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING
    }

    protected fun finalize() {
        release()
    }
}
