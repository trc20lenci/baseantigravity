package com.base.editor.ui.editor

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.base.editor.tts.TtsVoice
import com.base.editor.ui.theme.BaseColors
import com.base.editor.ui.theme.animatedSelectColor
import com.base.editor.ui.theme.pressable

/**
 * Раздел «Озвучка»: лист, который можно поднять вверх и листать вниз. Кнопка «Сгенерировать» — первой, дальше текст и список
 * голосов Piper. Готовый звук добавляется на аудиодорожку от позиции курсора.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsSheet(
    text: String, voice: TtsVoice, busy: Boolean,
    onText: (String) -> Unit, onVoice: (TtsVoice) -> Unit, onGenerate: () -> Unit, onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = BaseColors.DarkPanel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text("Озвучка текста", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            // кнопка — в начале, чтобы не искать её под списком голосов
            Box(
                Modifier.fillMaxWidth().pressable(enabled = !busy, onClick = onGenerate).clip(RoundedCornerShape(14.dp)).background(BaseColors.Primary).padding(vertical = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                    Text("  Озвучивание…", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                } else Text("Сгенерировать", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = text, onValueChange = onText, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
                placeholder = { Text("Введите текст, который нужно озвучить") },
            )
            Spacer(Modifier.height(14.dp))
            Text("Голос", color = BaseColors.MutedOnDark, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            TtsVoice.entries.forEach { v ->
                val on = v == voice
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).pressable { onVoice(v) }.clip(RoundedCornerShape(12.dp))
                        .background(animatedSelectColor(on, offColor = BaseColors.DarkSlot)).padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(v.label, color = if (on) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(if (v.language == "ru") "Русский" else "English", color = if (on) Color.Black.copy(alpha = .65f) else BaseColors.MutedOnDark, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
