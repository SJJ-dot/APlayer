package remix.myplayer.ui.widget.app

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import remix.myplayer.R
import remix.myplayer.service.Command
import remix.myplayer.service.MusicService
import remix.myplayer.service.MusicService.Companion.EXTRA_COMMAND
import remix.myplayer.ui.screen.playing.PlayQueueDialog
import remix.myplayer.ui.screen.playing.rememberPlayingDynamicColor
import remix.myplayer.ui.theme.FavoriteRed
import remix.myplayer.ui.theme.LocalTheme
import remix.myplayer.ui.widget.common.TextPrimary
import remix.myplayer.ui.widget.library.GlideCover
import remix.myplayer.util.ColorUtil
import remix.myplayer.util.Util
import remix.myplayer.util.ext.clickableWithoutRipple
import remix.myplayer.viewmodel.PlaybackViewModel
import remix.myplayer.viewmodel.PlayingScreenValue
import remix.myplayer.viewmodel.mainViewModel
import remix.myplayer.viewmodel.playbackViewModel
import kotlin.math.absoluteValue

private const val triggerThreshold = 10

/** 悬浮播放条的圆角（高度 56dp 时接近胶囊形） */
private val BottomBarShape = RoundedCornerShape(28.dp)

/** 底色偏浅时用于文字 / 图标的深色 */
private val DarkOnBar = Color(0xFF1C1B19)

/** 封面尺寸：比播放条（56dp）更高，上下压出条外，左右压住条的圆角 */
private val CoverSize = 64.dp
private val CoverShape = RoundedCornerShape(16.dp)

/** 封面左对齐到条的左边缘，把条左侧的圆角与背景完全压住 */
private val CoverStartInset = 0.dp

@Composable
fun BottomBar(modifier: Modifier = Modifier, vm: PlaybackViewModel = playbackViewModel) {
  val mainVM = mainViewModel
  val scope = rememberCoroutineScope()
  val playbackState by vm.playbackUiState.collectAsStateWithLifecycle()
  val interactionSource = remember { MutableInteractionSource() }
  val theme = LocalTheme.current

  // 底色与播放页背景保持一致：开启「跟随封面取色 / 主题色」时跟随歌曲动态变化，
  // 否则用当前表面色（深色主题下也不会和页面背景糊在一起）
  val dynamicColor = rememberPlayingDynamicColor()
  val barColor = dynamicColor ?: theme.dialogBackground
  // 底色可能偏浅也可能偏深，文字与图标按对比度自动切换
  val onBarColor = if (ColorUtil.isColorLight(barColor.toArgb())) DarkOnBar else Color.White

  var hasTriggerAct by remember { mutableStateOf(false) }
  var hasTriggerOp by remember { mutableStateOf(false) }
  var showPlayQueue by remember { mutableStateOf(false) }

  fun sendCommand(command: Int) {
    Util.sendLocalBroadcast(
      Intent(MusicService.ACTION_CMD).putExtra(EXTRA_COMMAND, command)
    )
  }

  // 悬浮圆角播放条：左右留边 + 圆角 + 阴影，与列表内容分离。
  // 阴影必须 clip = false、圆角只作用在 background 上，
  // 否则条上的封面会被条的圆角裁掉（封面需要溢出到条外压住圆角）。
  val baseModifier = modifier
    .fillMaxWidth()
    .padding(horizontal = 12.dp, vertical = 6.dp)
    .height(56.dp)
    .shadow(4.dp, BottomBarShape, clip = false)
    .background(barColor, BottomBarShape)

  val isSongValid = playbackState.song.valid()
  val interactionModifiers = if (isSongValid) {
    Modifier
      // 点击跳转播放页
      .clickableWithoutRipple(interactionSource) {
        scope.launch {
          mainVM.playingScreenState.animateTo(PlayingScreenValue.Expanded)
        }
      }
      // 垂直滑动跳转播放页
      .pointerInput(Unit) {
        detectVerticalDragGestures(
          onDragStart = { hasTriggerAct = false }
        ) { _, dragAmount ->
          if (dragAmount < -triggerThreshold && !hasTriggerAct) {
            hasTriggerAct = true
            scope.launch {
              mainVM.playingScreenState.animateTo(PlayingScreenValue.Expanded)
            }
          }
        }
      }
      // 水平滑动切换歌曲
      .pointerInput(Unit) {
        detectHorizontalDragGestures(
          onDragStart = { hasTriggerOp = false }
        ) { _, dragAmount ->
          if (dragAmount.absoluteValue > triggerThreshold && !hasTriggerOp) {
            hasTriggerOp = true
            Util.sendLocalBroadcast(
              Intent(MusicService.ACTION_CMD)
                .putExtra(
                  EXTRA_COMMAND,
                  if (dragAmount < 0) Command.SKIP_TO_NEXT else Command.SKIP_TO_PREVIOUS
                )
            )
          }
        }
      }
  } else {
    // 歌曲无效时，不响应任何操作
    Modifier
  }

  Row(
    modifier = baseModifier
      .semantics { contentDescription = "BottomBar" }
      .then(interactionModifiers),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    GlideCover(
      model = playbackState.song,
      circle = false,
      // 封面加载完成后取色：让播放条底色在主页就能跟随当前歌曲，而不必先打开播放页
      onBitmapLoaded = { vm.updateSwatch(it) },
      // requiredSize 会忽略父级（56dp 高的条）的约束，让封面真正大于条并溢出到条外
      modifier = Modifier
        .padding(start = CoverStartInset)
        .requiredSize(CoverSize)
        .clip(CoverShape)
    )

    Column(
      verticalArrangement = Arrangement.Center,
      modifier = Modifier
        .weight(1f)
        .fillMaxHeight()
        .padding(horizontal = 10.dp)
    ) {
      TextPrimary(
        text = playbackState.song.title,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = onBarColor
      )
      Spacer(modifier = Modifier.height(2.dp))
      // 用 TextPrimary 只是因为它支持指定颜色（副标题需要次要色）
      TextPrimary(
        text = playbackState.song.artist,
        fontSize = 12.sp,
        color = onBarColor.copy(alpha = 0.72f)
      )
    }

    // 收藏：已收藏为实心红心（与歌曲列表的标记一致），未收藏为描边
    BottomBarIconButton(
      onClick = { sendCommand(Command.LOVE) },
      contentDescription = "Favorite",
      iconRes = if (playbackState.isFavorite) {
        R.drawable.ic_favorite_filled_24dp
      } else {
        R.drawable.ic_favorite_24dp
      },
      tint = if (playbackState.isFavorite) FavoriteRed else onBarColor.copy(alpha = 0.6f)
    )

    // 播放 / 暂停：圆形按钮，用与底色相反的颜色，保证任何底色下都可见
    Box(
      modifier = Modifier.size(40.dp),
      contentAlignment = Alignment.Center
    ) {
      Box(
        modifier = Modifier
          .size(36.dp)
          .clip(CircleShape)
          .background(onBarColor)
          .clickableWithoutRipple(interactionSource) { sendCommand(Command.PLAY_PAUSE) },
        contentAlignment = Alignment.Center
      ) {
        Icon(
          painter = painterResource(
            if (playbackState.isPlaying) R.drawable.ic_pause_black_24dp
            else R.drawable.ic_play_arrow_black_24dp
          ),
          contentDescription = "PlayPause",
          tint = barColor,
          modifier = Modifier.size(22.dp)
        )
      }
    }

    // 播放列表（播放队列）
    BottomBarIconButton(
      onClick = { showPlayQueue = true },
      contentDescription = "PlayQueue",
      iconRes = R.drawable.ic_format_list_bulleted_white_24dp,
      tint = onBarColor.copy(alpha = 0.85f),
      modifier = Modifier.padding(end = 6.dp)
    )
  }

  PlayQueueDialog(
    visible = showPlayQueue,
    onDismissRequest = { showPlayQueue = false },
    musicState = playbackState
  )
}

@Composable
private fun BottomBarIconButton(
  onClick: () -> Unit,
  contentDescription: String,
  @DrawableRes iconRes: Int,
  tint: Color,
  modifier: Modifier = Modifier
) {
  val interactionSource = remember { MutableInteractionSource() }
  Box(
    modifier = modifier
      .size(40.dp)
      .clickableWithoutRipple(interactionSource) { onClick() },
    contentAlignment = Alignment.Center
  ) {
    Icon(
      painter = painterResource(iconRes),
      contentDescription = contentDescription,
      tint = tint,
      modifier = Modifier.size(22.dp)
    )
  }
}
