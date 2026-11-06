package com.eagalts.still.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eagalts.still.Access
import com.eagalts.still.Still
import com.eagalts.still.t

/** Корень: единственный экран — лента; всё остальное поверх неё. */
@Composable
fun StillApp(s: Still, dark: Boolean, onAsk: () -> Unit, feedController: FeedController? = null) {
    val p = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides p) {
        Box(Modifier.fillMaxSize().background(p.page)) {
            AnimatedContent(
                s.access == Access.NONE,
                transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(200)) },
                label = "access",
            ) { none ->
                if (none) Welcome(onAsk) else {
                    val ctl = feedController ?: remember { FeedController(androidx.compose.foundation.lazy.grid.LazyGridState()) }
                    LaunchedEffect(s.reveal, s.feed.gen) {
                        val id = s.reveal ?: return@LaunchedEffect
                        if (ctl.cells.any { it.key == id }) { ctl.reveal(id); s.reveal = null }
                    }
                    Box(Modifier.fillMaxSize()) {
                        // под шторкой лента уходит в размытие — шторка становится стеклом над твоими фото
                        val blur by androidx.compose.animation.core.animateFloatAsState(
                            if (s.sheet) 1f else 0f, fineSpring(1f, 300f), label = "blur",
                        )
                        Box(Modifier.fillMaxSize().graphicsLayer {
                            val r = 28.dp.toPx() * blur.coerceIn(0f, 1f)
                            if (android.os.Build.VERSION.SDK_INT >= 31 && r > 0.5f)
                                renderEffect = androidx.compose.ui.graphics.BlurEffect(r, r, androidx.compose.ui.graphics.TileMode.Clamp)
                        }) {
                            FeedScreen(s, ctl)
                            BottomLayer(s, ctl)
                        }
                        MarkSheet(s, ctl)
                        Viewer(s, ctl)
                    }
                }
            }
            ToastHost(s.toast)
            CrashDialog(s)
        }
    }
}

/**
 * Первый запуск: знак, одна фраза и одна кнопка. Знак «оживает» — по глади пробегает рябь, солнце встаёт.
 */
@Composable
private fun Welcome(onAsk: () -> Unit) {
    val p = LocalPalette.current
    val rise = remember { Animatable(0f) }
    val ripple = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        rise.animateTo(1f, spring(0.55f, 140f))
    }
    LaunchedEffect(Unit) {
        ripple.snapTo(1f); ripple.animateTo(0f, spring(0.25f, 90f))
    }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(96.dp)) { drawStillMark(size.width, (1f - rise.value) * -120f, rise.value.coerceIn(0f, 1f)) }
        Spacer(Modifier.height(20.dp))
        Text("Still", color = p.text, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = UiFont,
            modifier = Modifier.graphicsLayer { alpha = rise.value.coerceIn(0f, 1f); translationY = (1f - rise.value) * 24f })
        Spacer(Modifier.height(10.dp))
        Text(
            t("Галерея, которая сама держит порядок. Фото и видео не покидают телефон."),
            color = p.muted, fontSize = 16.sp, fontFamily = UiFont, textAlign = TextAlign.Center, lineHeight = 22.sp,
            modifier = Modifier.graphicsLayer { alpha = rise.value.coerceIn(0f, 1f) },
        )
        Spacer(Modifier.height(36.dp))
        Text(
            t("Открыть доступ к фото"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = UiFont,
            modifier = Modifier.bouncy(0.94f, onClick = onAsk).clip(RoundedCornerShape(50)).background(p.accent)
                .padding(horizontal = 28.dp, vertical = 15.dp),
        )
    }
}

/** «В прошлый раз Still упал»: текст стека и «Скопировать» (из Plume, `ui/CrashDialog.kt`). */
@Composable
private fun CrashDialog(s: Still) {
    val text = s.lastCrash ?: return
    val p = LocalPalette.current
    val ctx = LocalContext.current
    androidx.activity.compose.BackHandler { s.lastCrash = null }
    Box(Modifier.fillMaxSize().background(p.scrim), contentAlignment = Alignment.Center) {
        Column(
            Modifier.statusBarsPadding().navigationBarsPadding().padding(16.dp).fillMaxWidth()
                .fillMaxHeight(0.8f).clip(SquircleShape(Radius.l)).background(p.sheet).padding(20.dp)
        ) {
            Text(t("Приложение упало"), color = p.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = UiFont)
            Spacer(Modifier.height(6.dp))
            Text(t("Скопируйте текст ниже и пришлите — по нему найдём ошибку."), color = p.muted, fontSize = 15.sp, fontFamily = UiFont)
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.weight(1f).fillMaxWidth().clip(SquircleShape(Radius.m)).background(p.tile)
                    .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)
            ) { Text(text, color = p.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp) }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    t("Скопировать"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = UiFont,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).bouncy(0.95f) {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Still", text))
                        s.toast(t("Скопировано")); s.lastCrash = null
                    }.clip(RoundedCornerShape(50)).background(p.accent).padding(vertical = 14.dp),
                )
                Text(
                    t("Закрыть"), color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = UiFont,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).bouncy(0.95f) { s.lastCrash = null }
                        .clip(RoundedCornerShape(50)).background(p.tile).padding(vertical = 14.dp),
                )
            }
        }
    }
}

