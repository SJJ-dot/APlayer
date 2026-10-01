package remix.myplayer.ui.screen.playing

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import remix.myplayer.R
import remix.myplayer.data.prefs.SettingPrefs
import remix.myplayer.ui.theme.LocalTheme
import remix.myplayer.ui.theme.StatusBarBackground
import remix.myplayer.util.ThemeUtil
import remix.myplayer.viewmodel.playbackViewModel
import remix.myplayer.viewmodel.settingViewModel

/**
 * 播放页的「动态底色」——与「播放页背景」设置保持一致。
 *
 * 供播放页渐变顶部、底部播放条底色、状态栏图标判断共用，保证三处颜色统一。
 * 返回 null 表示该场景没有与歌曲相关的颜色（深色主题、或未开启彩色背景）。
 */
@Composable
fun rememberPlayingDynamicColor(): Color? {
  val theme = LocalTheme.current
  val settingState by settingViewModel.settingsState.collectAsStateWithLifecycle()
  val swatch by playbackViewModel.swatch.collectAsStateWithLifecycle()
  return when {
    !theme.isLight -> null
    settingState.playingScreen.background == SettingPrefs.BACKGROUND_ADAPTIVE_COLOR -> Color(swatch.rgb)
    settingState.playingScreen.background == SettingPrefs.BACKGROUND_THEME -> theme.primary
    else -> null
  }
}

@Composable
fun PlayingContainer(isVisible: Boolean = true, content: @Composable () -> Unit) {
  val settingState by settingViewModel.settingsState.collectAsStateWithLifecycle()
  val theme = LocalTheme.current
  val dynamicColor = rememberPlayingDynamicColor()
  val initialColor = Color(
    ThemeUtil.resolveColor(
      LocalContext.current,
      R.attr.colorSurface,
      if (theme.isLight) Color.White.value.toInt() else Color.Black.value.toInt()
    )
  )
  // 渐变到封面取色的动画；只有「跟随封面取色」这一档才会用到它的中间值
  val animatedColor = remember(initialColor) { Animatable(initialValue = initialColor) }
  LaunchedEffect(dynamicColor, initialColor) {
    animatedColor.animateTo(dynamicColor ?: initialColor, animationSpec = tween(600))
  }

  val brush: Brush? = when {
    !theme.isLight ->
      Brush.verticalGradient(colors = listOf(theme.mainBackground, theme.mainBackground))

    settingState.playingScreen.background == SettingPrefs.BACKGROUND_ADAPTIVE_COLOR ->
      Brush.verticalGradient(colors = listOf(animatedColor.value, initialColor))

    settingState.playingScreen.background == SettingPrefs.BACKGROUND_THEME ->
      Brush.verticalGradient(colors = listOf(theme.primary, initialColor))

    else -> null
  }

  // 状态栏压在渐变「顶部」的颜色上，所以图标深浅要按这个颜色判断
  val statusBarColor = dynamicColor ?: theme.mainBackground
  // 只有播放页可见时才接管状态栏外观，收起后还原成默认（跟随主题色顶栏）
  LaunchedEffect(isVisible, statusBarColor) {
    StatusBarBackground.set(if (isVisible) statusBarColor else null)
  }
  DisposableEffect(Unit) {
    onDispose { StatusBarBackground.set(null) }
  }

  Container(brush = brush, content = content)
}

/**
 * 系统自由小窗 / 悬浮小窗底部的安全间距。
 *
 * 小窗模式下 WindowInsets.navigationBars 不报告 inset（小窗内无系统导航栏），
 * 但国产 ROM（MIUI/EMUI/ColorOS 等）会在小窗底部渲染一条手势 / 拖拽条，
 * 直接盖住播放界面底部的 PlayingUtilityBar。多窗口模式下补一个固定底部间距来避让。
 */
private val SmallWindowBottomSafePadding = 24.dp

@Composable
private fun Container(
  brush: Brush?,
  content: @Composable () -> Unit
) {
  // 多窗口 / 系统小窗下 navigationBars 不报告 inset，但国产 ROM 会在小窗底部画手势 / 拖拽条盖住内容；
  // 保证底部总留白 ≥ SmallWindowBottomSafePadding（safeDrawing 已给的 + 额外补的，不重复叠加）。
  val inMultiWindow = rememberInMultiWindowMode()
  val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
  val extraBottom = if (inMultiWindow) {
    (SmallWindowBottomSafePadding - safeBottom).coerceAtLeast(0.dp)
  } else {
    0.dp
  }
  // 背景在 safeDrawingPadding 之前，铺满全屏（沉浸式）；内容由 safeDrawingPadding 内缩避开 insets。
  val modifier = Modifier
    .fillMaxSize()
    .then(
      if (brush != null) Modifier.background(
        brush = brush,
        shape = RectangleShape
      ) else Modifier
    )
    .safeDrawingPadding()
    .padding(bottom = extraBottom)

  Column(modifier = modifier) {
    content()
  }
}

/**
 * 当前是否处于系统多窗口 / 自由小窗模式。
 *
 * isInMultiWindowMode 本身不是 Compose State，不会主动触发重组；这里以 LocalConfiguration 为 key
 * remember，进入 / 退出小窗时窗口尺寸变化触发 configuration change → 重组重读最新值。
 * API 24 以下不存在多窗口，固定返回 false。
 */
@Composable
private fun rememberInMultiWindowMode(): Boolean {
  val activity = LocalActivity.current ?: return false
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
  val configuration = LocalConfiguration.current
  return remember(activity, configuration) { activity.isInMultiWindowMode }
}
