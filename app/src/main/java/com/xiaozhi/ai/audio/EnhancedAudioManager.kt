package com.xiaozhi.ai.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.xiaozhi.ai.audio.utils.OpusDecoder
import com.xiaozhi.ai.audio.utils.OpusEncoder
import com.xiaozhi.ai.audio.utils.OpusStreamPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream

/**
 * 音频事件
 */
sealed class AudioEvent {
    data class AudioData(val data: ByteArray) : AudioEvent()
    data class Error(val message: String) : AudioEvent()
}

/**
 * 增强版音频管理器
 * 使用真正的Opus编解码器和流式播放
 */
class EnhancedAudioManager(private val context: Context) {
    companion object {
        private const val TAG = "EnhancedAudioManager"
        private const val RECORD_SAMPLE_RATE = 16000
        private const val PLAY_SAMPLE_RATE = 16000
        private const val CHANNELS = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_DURATION_MS = 60
        private const val FRAME_SIZE = RECORD_SAMPLE_RATE * FRAME_DURATION_MS / 1000 * 2 // 16bit = 2 bytes (1920 bytes)
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var isPlayingState = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _audioEvents = MutableSharedFlow<AudioEvent>()
    val audioEvents: SharedFlow<AudioEvent> = _audioEvents

    // AEC和NS处理器
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    // Opus编解码器
    private var opusEncoder: OpusEncoder? = null
    private var opusDecoder: OpusDecoder? = null
    private var streamPlayer: OpusStreamPlayer? = null
    
    // 音频播放队列 (Channel 保证严格 FIFO 与非阻塞投递)
    private val playbackChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var playbackJob: Job? = null

    /**
     * 初始化音频系统
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun initialize(): Boolean {
        return try {
            // 初始化Opus编解码器
            opusEncoder = OpusEncoder(RECORD_SAMPLE_RATE, 1, FRAME_DURATION_MS)
            opusDecoder = OpusDecoder(PLAY_SAMPLE_RATE, 1, FRAME_DURATION_MS)
            streamPlayer = OpusStreamPlayer(PLAY_SAMPLE_RATE, 1, FRAME_DURATION_MS, context)
            
            setupAudioRecord()
            ensurePlaybackJob()
            Log.d(TAG, "增强版音频系统初始化成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "增强版音频系统初始化失败", e)
            false
        }
    }

    /**
     * 设置录音器
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun setupAudioRecord() {
        if (!checkPermissions()) {
            throw SecurityException("缺少录音权限")
        }

        val bufferSize = AudioRecord.getMinBufferSize(RECORD_SAMPLE_RATE, CHANNELS, AUDIO_FORMAT)
        
        // 使用 VOICE_RECOGNITION：专为语音识别调优，不进入通话模式，避免听筒抢占与降噪门限将人声当作噪声裁切
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            RECORD_SAMPLE_RATE,
            CHANNELS,
            AUDIO_FORMAT,
            maxOf(bufferSize * 4, FRAME_SIZE * 4)
        )

        // 设置AEC和NS
        setupAudioEffects()
    }

    /**
     * 设置音频效果（AEC+NS）
     */
    private fun setupAudioEffects() {
        audioRecord?.let { record ->
            try {
                // 回音消除
                if (AcousticEchoCanceler.isAvailable()) {
                    acousticEchoCanceler = AcousticEchoCanceler.create(record.audioSessionId)
                    acousticEchoCanceler?.enabled = true
                    Log.d(TAG, "AEC已启用")
                } else {
                    Log.w(TAG, "设备不支持AEC")
                }

                // 噪声抑制
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)
                    noiseSuppressor?.enabled = true
                    Log.d(TAG, "NS已启用")
                } else {
                    Log.w(TAG, "设备不支持NS")
                }
            } catch (e: Exception) {
                Log.e(TAG, "设置音频效果失败", e)
            }
        }
    }

    /**
     * 确保音频播放工作协程在运行（保持单一协程顺序消费，防止并发交叉播报导致叠字）
     */
    @Synchronized
    private fun ensurePlaybackJob() {
        if (playbackJob?.isActive == true) return
        playbackJob = scope.launch {
            try {
                for (opusData in playbackChannel) {
                    if (!isActive) break
                    try {
                        opusDecoder?.let { decoder ->
                            val pcmData = decoder.decode(opusData)
                            if (pcmData != null && pcmData.isNotEmpty()) {
                                streamPlayer?.write(pcmData)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解码或写入音频失败", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "音频播放工作协程异常", e)
            } finally {
                isPlayingState = false
            }
        }
    }

    /**
     * 开始录音
     */
    fun startRecording() {
        if (isRecording) return

        audioRecord?.let { record ->
            try {
                record.startRecording()
                isRecording = true
                Log.d(TAG, "开始录音")

                scope.launch {
                    val readBuffer = ByteArray(FRAME_SIZE)
                    val accumulator = ByteArrayOutputStream()
                    var frameCounter = 0L

                    while (isRecording) {
                        val bytesRead = record.read(readBuffer, 0, readBuffer.size)
                        if (bytesRead > 0) {
                            // 计算当前块的 RMS 能量
                            var sumSquares = 0L
                            val sampleCount = bytesRead / 2
                            for (i in 0 until bytesRead step 2) {
                                val sample = ((readBuffer[i].toInt() and 0xFF) or (readBuffer[i + 1].toInt() shl 8)).toShort()
                                sumSquares += sample * sample
                            }
                            val rms = kotlin.math.sqrt((sumSquares.toDouble() / maxOf(1, sampleCount))).toFloat()

                            // 将读取的原始 PCM 字节写入累积器，防止底层驱动以非标准分片返回时导致丢帧
                            accumulator.write(readBuffer, 0, bytesRead)

                            while (accumulator.size() >= FRAME_SIZE) {
                                val allBytes = accumulator.toByteArray()
                                val frameToEncode = allBytes.copyOfRange(0, FRAME_SIZE)
                                accumulator.reset()
                                if (allBytes.size > FRAME_SIZE) {
                                    accumulator.write(allBytes, FRAME_SIZE, allBytes.size - FRAME_SIZE)
                                }

                                frameCounter++
                                if (frameCounter % 25 == 0L) {
                                    Log.d(TAG, "麦克风正在推流: frame=$frameCounter, rms=$rms")
                                }

                                opusEncoder?.let { encoder ->
                                    val opusData = encoder.encode(frameToEncode)
                                    if (opusData != null) {
                                        _audioEvents.emit(AudioEvent.AudioData(opusData))
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "录音失败", e)
                scope.launch {
                    _audioEvents.emit(AudioEvent.Error("录音失败: ${e.message}"))
                }
            }
        }
    }

    /**
     * 停止录音
     */
    fun stopRecording() {
        if (!isRecording) return

        isRecording = false
        audioRecord?.stop()
        Log.d(TAG, "停止录音")
    }

    /**
     * 播放音频数据（流式分片投递）
     */
    fun playAudio(audioData: ByteArray) {
        ensurePlaybackJob()
        isPlayingState = true
        playbackChannel.trySend(audioData)
    }

    /**
     * 停止播放（清空排队数据并暂停底层播放器）
     */
    fun stopPlaying() {
        isPlayingState = false
        // 清空尚未解码的音频分片（用于打断或中止）
        while (playbackChannel.tryReceive().isSuccess) {
            // drain
        }
        streamPlayer?.stop()
        Log.d(TAG, "停止播放并清空音频队列")
    }

    /**
     * 开始流式播放
     */
    fun startStreamPlayback(opusDataFlow: SharedFlow<ByteArray>) {
        ensurePlaybackJob()
        isPlayingState = true
        scope.launch {
            opusDataFlow.collect { opusData ->
                playbackChannel.trySend(opusData)
            }
        }
        Log.d(TAG, "开始流式播放")
    }

    /**
     * 停止流式播放
     */
    fun stopStreamPlayback() {
        stopPlaying()
    }

    /**
     * 等待所有待播放音频完全播放完成
     */
    suspend fun waitForPlaybackCompletion() {
        // 1. 等待 Channel 中的所有数据包被解码并写入底层播放器
        while (!playbackChannel.isEmpty) {
            delay(20)
        }
        // 2. 短暂等待确保最后一帧写入 AudioTrack
        delay(50)
        // 3. 等待 AudioTrack 中的音频全部播放完毕
        streamPlayer?.waitForPlaybackCompletion()
        isPlayingState = false
    }

    /**
     * 检查权限
     */
    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        stopRecording()
        stopPlaying()
        playbackJob?.cancel()
        playbackChannel.close()
        
        acousticEchoCanceler?.release()
        noiseSuppressor?.release()
        
        audioRecord?.release()
        
        // 释放Opus编解码器资源
        opusEncoder?.release()
        opusDecoder?.release()
        streamPlayer?.release()
        
        scope.cancel()
        Log.d(TAG, "增强版音频资源已清理")
    }

    /**
     * 获取录音状态
     */
    fun isRecording(): Boolean = isRecording

    /**
     * 获取播放状态
     */
    fun isPlaying(): Boolean = isPlayingState
    
    /**
     * 测试音频播放（生成一个简单的测试音调）
     */
    fun testAudioPlayback() {
        scope.launch {
            try {
                // 创建一个独立的测试播放器
                val testPlayer = OpusStreamPlayer(PLAY_SAMPLE_RATE, 1, FRAME_DURATION_MS, context)
                
                // 生成一个440Hz的测试音调（A4音符）
                val sampleRate = PLAY_SAMPLE_RATE
                val duration = 1.0 // 1秒
                val frequency = 440.0
                val samples = (sampleRate * duration).toInt()
                val pcmData = ByteArray(samples * 2) // 16-bit = 2 bytes per sample
                
                for (i in 0 until samples) {
                    val sample = (32767 * kotlin.math.sin(2 * kotlin.math.PI * frequency * i / sampleRate)).toInt().toShort()
                    pcmData[i * 2] = (sample.toInt() and 0xFF).toByte()
                    pcmData[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
                }
                
                // 直接发送PCM数据到测试播放器
                val testFlow = flow {
                    emit(pcmData)
                }
                
                testPlayer.start(testFlow)
                Log.d(TAG, "开始播放测试音调")
                
                // 等待播放完成
                delay(1500)
                testPlayer.stop()
                testPlayer.release()
                Log.d(TAG, "测试音调播放完成")
                
            } catch (e: Exception) {
                Log.e(TAG, "测试音频播放失败", e)
            }
        }
    }
}