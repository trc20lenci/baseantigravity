package com.base.editor.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.base.editor.core.CanvasBg
import com.base.editor.core.MediaType
import com.base.editor.data.Thumbs
import com.base.editor.ui.theme.Lucide
import com.base.editor.ui.theme.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class BgSubTab { MAIN, COLOR, IMAGE, BLUR }

private val BG_COLOR_SWATCHES = listOf(
    0xFFFFFFFFL, 0xFFE0E0E0L, 0xFF888888L, 0xFF333333L, 0xFF000000L,
    0xFFFFE600L, 0xFFFFB300L, 0xFFFF9800L, 0xFFFF2D55L, 0xFFEC4899L,
    0xFF7C3AEDL, 0xFF3F51B5L, 0xFF2196F3L, 0xFF00E5FFL, 0xFF00CCDDL,
    0xFF00FF66L, 0xFF4CAF50L, 0xFF795548L
)

@Composable
fun BackgroundsBar(
    current: CanvasBg,
    blurRadius: Float = 1.0f,
    customImageUri: String? = null,
    projectBgImages: List<String> = emptyList(),
    previewClipUri: String? = null,
    previewClipType: MediaType? = null,
    previewClipTimeMs: Long = 0L,
    onPick: (CanvasBg) -> Unit,
    onBlurRadiusChange: (Float) -> Unit = {},
    onPickCustomImage: (Uri) -> Unit = {},
    onSelectImage: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    var subTab by remember { mutableStateOf(BgSubTab.MAIN) }

    when (subTab) {
        BgSubTab.MAIN -> MainTab(
            onOpenColor = { subTab = BgSubTab.COLOR },
            onOpenImage = { subTab = BgSubTab.IMAGE },
            onOpenBlur = { subTab = BgSubTab.BLUR },
            onBack = onBack
        )

        BgSubTab.COLOR -> ColorTab(
            current = current,
            onPick = onPick,
            onConfirm = { subTab = BgSubTab.MAIN }
        )

        BgSubTab.IMAGE -> ImageTab(
            current = current,
            customImageUri = customImageUri,
            projectBgImages = projectBgImages,
            onPick = onPick,
            onPickCustomImage = onPickCustomImage,
            onSelectImage = onSelectImage,
            onConfirm = { subTab = BgSubTab.MAIN }
        )

        BgSubTab.BLUR -> BlurTab(
            current = current,
            blurRadius = blurRadius,
            previewClipUri = previewClipUri,
            previewClipType = previewClipType,
            previewClipTimeMs = previewClipTimeMs,
            onPick = onPick,
            onBlurRadiusChange = onBlurRadiusChange,
            onConfirm = { subTab = BgSubTab.MAIN }
        )
    }
}

/** Вкладка выбора раздела: Цвет / Изображение / Размытие */
@Composable
private fun MainTab(
    onOpenColor: () -> Unit,
    onOpenImage: () -> Unit,
    onOpenBlur: () -> Unit,
    onBack: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxSize()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(44.dp)
                .pressable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(Lucide.ChevronLeft, "Назад", tint = Color.White, modifier = Modifier.size(24.dp))
        }

        Spacer(Modifier.width(8.dp))

        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MainCategoryButton(
                icon = Lucide.PaintBucket,
                label = "Цвет",
                onClick = onOpenColor
            )
            MainCategoryButton(
                icon = Lucide.Image,
                label = "Изображение",
                onClick = onOpenImage
            )
            MainCategoryButton(
                icon = Lucide.Droplet,
                label = "Размытие",
                onClick = onOpenBlur
            )
        }
    }
}

@Composable
private fun MainCategoryButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(72.dp)
            .pressable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/** Вкладка «Цвет»: чистый ряд кружков цветов без пипетки и спектра, отцентрован по вертикали */
@Composable
private fun ColorTab(
    current: CanvasBg,
    onPick: (CanvasBg) -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(BG_COLOR_SWATCHES, key = { it }) { colorLong ->
                val colorInt = colorLong.toInt()
                val isSelected = current != CanvasBg.BLUR && current != CanvasBg.IMAGE && current.argb == colorInt
                Box(
                    Modifier
                        .size(38.dp)
                        .pressable { onPick(CanvasBg.fromColor(colorInt)) }
                        .clip(CircleShape)
                        .background(Color(colorLong))
                        .then(
                            if (isSelected) Modifier.border(3.dp, Color.White, CircleShape)
                            else Modifier.border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                        )
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .pressable(onClick = onConfirm),
            contentAlignment = Alignment.Center
        ) {
            Icon(Lucide.Check, "Подтвердить", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

/** Вкладка «Изображение»: 🚫, [+] и сохраненные квадраты фото проекта, отцентрованы по вертикали */
@Composable
private fun ImageTab(
    current: CanvasBg,
    customImageUri: String?,
    projectBgImages: List<String>,
    onPick: (CanvasBg) -> Unit,
    onPickCustomImage: (Uri) -> Unit,
    onSelectImage: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onPickCustomImage(uri)
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Карточка 🚫: выключить фон-картинку
            item {
                val isNoneSelected = current != CanvasBg.IMAGE
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF262629))
                        .then(
                            if (isNoneSelected) Modifier.border(2.5.dp, Color.White, RoundedCornerShape(12.dp))
                            else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        )
                        .pressable { onPick(CanvasBg.BLACK) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Lucide.Ban, "Без изображения", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(22.dp))
                }
            }

            // Карточка [+]: добавить свое фото
            item {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF262629))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .pressable { photoPickerLauncher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Lucide.ImagePlus, "Добавить фото", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }

            // Квадраты добавленных пользователем фото этого проекта
            items(projectBgImages, key = { it }) { uriStr ->
                val isSelected = current == CanvasBg.IMAGE && customImageUri == uriStr
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF262629))
                        .then(
                            if (isSelected) Modifier.border(2.5.dp, Color.White, RoundedCornerShape(12.dp))
                            else Modifier.border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        )
                        .pressable {
                            onSelectImage(uriStr)
                        }
                ) {
                    ImageThumbnail(uriStr = uriStr, modifier = Modifier.fillMaxSize())
                }
            }
        }

        Spacer(Modifier.width(10.dp))

        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .pressable(onClick = onConfirm),
            contentAlignment = Alignment.Center
        ) {
            Icon(Lucide.Check, "Подтвердить", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

/** Загрузчик миниатюры для карточки изображения */
@Composable
private fun ImageThumbnail(uriStr: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, uriStr) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(uriStr)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    android.graphics.ImageDecoder.decodeBitmap(
                        android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)
                    ) { dec, _, _ ->
                        dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        dec.setTargetSize(128, 128)
                    }
                } else {
                    ctx.contentResolver.openInputStream(uri)?.use { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream)
                    }
                }
            }.getOrNull()
        }
    }
    bitmap?.let { bmp ->
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } ?: Box(modifier.background(Color(0xFF262629)))
}

/** Вкладка «Размытие»: 🚫 и 4 градации размытия, отцентрованы по вертикали */
@Composable
private fun BlurTab(
    current: CanvasBg,
    blurRadius: Float,
    previewClipUri: String?,
    previewClipType: MediaType?,
    previewClipTimeMs: Long,
    onPick: (CanvasBg) -> Unit,
    onBlurRadiusChange: (Float) -> Unit,
    onConfirm: () -> Unit,
) {
    val ctx = LocalContext.current
    val isBlurActive = current == CanvasBg.BLUR

    val baseBmp by produceState<Bitmap?>(null, previewClipUri, previewClipTimeMs) {
        if (previewClipUri != null && previewClipType != null) {
            value = withContext(Dispatchers.IO) {
                Thumbs.load(ctx, previewClipUri, previewClipType, previewClipTimeMs, 120)?.bitmap
            }
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Карточка 0: 🚫 (без размытия)
            val isNoneSelected = !isBlurActive
            item {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF262629))
                        .then(
                            if (isNoneSelected) Modifier.border(2.5.dp, Color.White, RoundedCornerShape(12.dp))
                            else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        )
                        .pressable { onPick(CanvasBg.BLACK) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Lucide.Ban, "Без размытия", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(22.dp))
                }
            }

            // 4 уровня размытия
            val blurLevels = listOf(0.3f, 0.8f, 1.6f, 2.8f)

            itemsIndexed(blurLevels) { index, radius ->
                val isSelected = isBlurActive && kotlin.math.abs(blurRadius - radius) < 0.45f
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF262629))
                        .then(
                            if (isSelected) Modifier.border(2.5.dp, Color.White, RoundedCornerShape(12.dp))
                            else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        )
                        .pressable {
                            onBlurRadiusChange(radius)
                            onPick(CanvasBg.BLUR)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(3.dp)
                            .clip(RoundedCornerShape(8.dp))
                    ) {
                        val bmp = baseBmp
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .blur(((index + 1) * 3).dp)
                            )
                        } else {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                Color(0xFF3B4866),
                                                Color(0xFF2A283B),
                                                Color(0xFF1E1E26)
                                            )
                                        )
                                    )
                            )
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .align(Alignment.BottomCenter)
                                .background(Color.White.copy(alpha = (0.4f + index * 0.18f).coerceAtMost(0.95f)))
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(10.dp))

        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .pressable(onClick = onConfirm),
            contentAlignment = Alignment.Center
        ) {
            Icon(Lucide.Check, "Подтвердить", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}
