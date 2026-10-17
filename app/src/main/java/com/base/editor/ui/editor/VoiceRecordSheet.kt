package com.base.editor.ui.editor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import com.base.editor.ui.theme.BaseColors
import com.base.editor.ui.theme.Lucide
import com.base.editor.ui.theme.pressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private enum class RecordState { READY, COUNTDOWN, RECORDING }

@Composable
fun VoiceRecordSheet(
    onRecorded: (filePath: String, durationMs: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf(RecordState.READY) }
    var countdownValue by remember { mutableStateOf(3) }
    var recordStartTime by remember { mutableLongStateOf(0L) }
    var recordElapsedMs by remember { mutableLongStateOf(0L) }
    var currentOutputFile by remember { mutableStateOf<File?>(null) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) {
            state = RecordState.COUNTDOWN
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                recorder?.stop()
                recorder?.release()
            }
            recorder = null
        }
    }

    // Таймер записи
    LaunchedEffect(state) {
        if (state == RecordState.RECORDING) {
            while (state == RecordState.RECORDING) {
                recordElapsedMs = SystemClock.uptimeMillis() - recordStartTime
                delay(50)
            }
        }
    }

    fun stopAndFinishRecording() {
        val rec = recorder
        recorder = null
        val file = currentOutputFile
        runCatching {
            rec?.stop()
            rec?.release()
        }
        state = RecordState.READY
        if (file != null && file.exists() && file.length() > 0) {
            val retriever = MediaMetadataRetriever()
            val dur = runCatching {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }.getOrNull() ?: recordElapsedMs
            runCatching { retriever.release() }
            onRecorded(file.absolutePath, dur)
        } else {
            onDismiss()
        }
    }

    fun startRecordingInternal() {
        val file = File(ctx.cacheDir, "voice_record_${System.currentTimeMillis()}.m4a")
        currentOutputFile = file
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(ctx)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(44100)
            rec.setAudioEncodingBitRate(128000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            recordStartTime = SystemClock.uptimeMillis()
            recordElapsedMs = 0L
            state = RecordState.RECORDING
        } catch (e: Exception) {
            rec.release()
            recorder = null
            state = RecordState.READY
        }
    }

    fun startCountdown() {
        if (!hasPermission) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        state = RecordState.COUNTDOWN
        countdownValue = 3
        scope.launch {
            delay(700)
            countdownValue = 2
            delay(700)
            countdownValue = 1
            delay(700)
            startRecordingInternal()
        }
    }

    Dialog(
        onDismissRequest = {
            if (state == RecordState.RECORDING) {
                stopAndFinishRecording()
            } else {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnClickOutside = state != RecordState.RECORDING,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) {
            window?.apply {
                setGravity(Gravity.BOTTOM)
                setDimAmount(0.35f)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color(0xFF1E1F24))
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Drag handle
            Box(
                Modifier
                    .size(36.dp, 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.25f))
            )

            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (state) {
                        RecordState.READY -> "Запись голоса"
                        RecordState.COUNTDOWN -> "Приготовьтесь..."
                        RecordState.RECORDING -> "Идет запись..."
                    },
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .pressable {
                            if (state == RecordState.RECORDING) stopAndFinishRecording() else onDismiss()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Lucide.X, "Закрыть", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(28.dp))

            // Центральная интерактивная зона
            when (state) {
                RecordState.COUNTDOWN -> {
                    AnimatedContent(
                        targetState = countdownValue,
                        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                        label = "countdown"
                    ) { count ->
                        Text(
                            text = "$count",
                            color = BaseColors.Primary,
                            fontSize = 72.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("Запись начнется автоматически", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                }
                RecordState.RECORDING -> {
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.22f,
                        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                        label = "pulseScale"
                    )

                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(96.dp)
                                .scale(pulseScale)
                                .clip(CircleShape)
                                .background(Color(0xFFFF2D55).copy(alpha = 0.25f))
                        )
                        Box(
                            Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF2D55))
                                .pressable { stopAndFinishRecording() },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White)
                            )
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    val totalSec = recordElapsedMs / 1000
                    val msFrac = (recordElapsedMs % 1000) / 100
                    val min = totalSec / 60
                    val sec = totalSec % 60
                    val timeStr = "%02d:%02d.%d".format(min, sec, msFrac)

                    Text(timeStr, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Нажмите, чтобы остановить и добавить на дорожку", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                }
                RecordState.READY -> {
                    Box(
                        Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(BaseColors.Primary)
                            .pressable { startCountdown() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Lucide.Mic, "Начать запись", tint = Color.Black, modifier = Modifier.size(34.dp))
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("Нажмите на микрофон для записи", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                    Text("Голос добавится на дорожку в текущую позицию курсора", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}
