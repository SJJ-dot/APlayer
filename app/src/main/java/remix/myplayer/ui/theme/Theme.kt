package remix.myplayer.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import remix.myplayer.util.ColorUtil

/**
 * 状态栏背后的实际底色。
 *
 * 状态栏是沉浸式的（透明），所以图标深浅必须跟随它**实际压着的颜色**，而不是主题明暗：
 * - 主页及各类带顶栏的页面，状态栏压在主题色顶栏上（深色主题色 → 需要浅色图标）；
 * - 播放页压在随封面变化的渐变顶部（浅色 → 深色图标，深色 → 浅色图标）。
 *
 * 为 null 表示「跟随主题色顶栏」，即绝大多数页面的默认情况；
 * 少数背景特殊的页面（如播放页）通过 [set] 声明自己的颜色，离开时还原为 null。
 */
object StatusBarBackground {

  var color: Color? by mutableStateOf<Color?>(null)
    private set

  fun set(value: Color?) {
    color = value
  }
}

private val DarkColorScheme = darkColorScheme(
  primary = Purple80,
  secondary = PurpleGrey80,
  tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
  primary = Purple40,
  secondary = PurpleGrey40,
  tertiary = Pink40

  /* Other default colors to override
  background = Color(0xFFFFFBFE),
  surface = Color(0xFFFFFBFE),
  onPrimary = Color.White,
  onSecondary = Color.White,
  onTertiary = Color.White,
  onBackground = Color(0xFF1C1B1F),
  onSurface = Color(0xFF1C1B1F),
  */
)

@Composable
fun APlayerTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit
) {
//    val colorScheme = when {
//        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
//            val context = LocalContext.current
//            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
//        }
//
//        darkTheme -> DarkColorScheme
//        else -> LightColorScheme
//
//    }.copy(
//        surfaceContainer = Color(
//            Theme.resolveColor(
//                LocalContext.current,
//                R.attr.background_color_main
//            )
//        ),
//        primary = Color(ThemeStore.materialPrimaryColor)
//    )
  val theme = LocalTheme.current
  val colorScheme = lightColorScheme(
    primary = theme.primary,
    secondary = theme.secondary,
  )
  val view = LocalView.current
  // 状态栏图标深浅跟随其背后的实际底色；默认取主题色顶栏的颜色
  val statusBarBackground = StatusBarBackground.color ?: theme.primary
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as Activity).window
//      window.statusBarColor = colorScheme.primary.toArgb()
      WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
        ColorUtil.isColorLight(statusBarBackground.toArgb())
    }
  }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    content = content
  )
}