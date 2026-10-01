package remix.myplayer.ui.widget.library.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import remix.myplayer.R
import remix.myplayer.data.model.audio.APlayerModel
import remix.myplayer.data.model.audio.Song
import remix.myplayer.ui.theme.FavoriteRed
import remix.myplayer.ui.theme.LocalTheme
import remix.myplayer.ui.theme.highLightText
import remix.myplayer.ui.theme.popupButton
import remix.myplayer.ui.widget.common.TextPrimary
import remix.myplayer.ui.widget.common.TextSecondary
import remix.myplayer.ui.widget.library.GlideCover
import remix.myplayer.ui.widget.popup.SongPopupButton
import remix.myplayer.viewmodel.LocalFavoriteSongIds
import remix.myplayer.viewmodel.libraryViewModel

/** 卡片圆角 */
private val CardShape = RoundedCornerShape(12.dp)

/** 卡片左右外边距：让 item 之间露出列表底色，形成独立卡片 */
private val CardHorizontalMargin = 10.dp

/** 卡片上下外边距（一半）：相邻卡片间距即两倍于此 */
private val CardVerticalMargin = 3.dp

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ListSong(
  modifier: Modifier = Modifier,
  song: Song,
  modelParent: APlayerModel,
  selected: Boolean,
  playing: Boolean,
  /** 正在播放的行是否用强调色高亮序号与标题（播放队列用它把当前播放的歌曲标出来） */
  highlightPlaying: Boolean = false,
  popupEnabled: Boolean = true,
  num: Int? = null,
  onClickSong: () -> Unit,
  onLongClickSong: () -> Unit,
  /** 行尾操作区；为空时使用默认的「更多」菜单。播放队列用它替换为「移出队列」按钮 */
  trailing: (@Composable () -> Unit)? = null,
) {
  val theme = LocalTheme.current
  val libraryVM = libraryViewModel
  // 收藏状态由根部（LocalFavoriteSongIds）统一订阅后提供，item 无需各自订阅收藏数据
  val favorite = song.id in LocalFavoriteSongIds.current

  Box(
    modifier = modifier
      .fillMaxWidth()
      // 卡片外边距在组件内部完成：调用方仍只给 height，行高与间距无需在各列表重复配置
      .padding(horizontal = CardHorizontalMargin, vertical = CardVerticalMargin)
      .clip(CardShape)
      .background(theme.mainBackground)
      // 选中态以半透明高亮**叠加**在卡片底色上，而不是替换底色：
      // 卡片保持不透明，浅色主题下才不会透出列表底色
      .background(if (selected) theme.select else Color.Transparent)
      // 极细描边：浅色封面/纯黑主题下也能看出卡片轮廓
      .border(0.5.dp, theme.textSecondary.copy(alpha = 0.12f), CardShape)
      .combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = ripple(color = theme.ripple),
        onClick = { onClickSong() },
        onLongClick = { onLongClickSong() }
      ),
    contentAlignment = Alignment.CenterStart
  ) {
    // 正在播放：左侧高亮竖条（圆角、垂直居中，比贴边的直角长条更精致）
    if (playing) {
      Box(
        modifier = Modifier
          .align(Alignment.CenterStart)
          .padding(start = 3.dp)
          .width(3.dp)
          .fillMaxHeight(0.42f)
          .background(theme.highLightText(), RoundedCornerShape(1.5.dp))
      )
    }

    Row(
      modifier = Modifier.fillMaxSize(),
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (num != null) {
        TextPrimary(
          if (num > 999) "999+" else num.toString(),
          textAlign = TextAlign.Center,
          // 当前播放行：序号也用强调色，与左侧竖条、标题高亮呼应
          color = if (playing && highlightPlaying) theme.highLightText() else theme.textPrimary,
          modifier = Modifier
            .width(40.dp)
            .padding(horizontal = 4.dp)
        )
      } else {
        Spacer(modifier = Modifier.width(16.dp))
      }

      // 封面：专辑封面用圆角方形，与专辑/歌手列表（GridItem/ListItem 均为 circle = false）保持一致；
      // 外面套一圈极细描边，浅色封面在浅色背景上也有轮廓，不会“糊”成一片
      val coverShape = RoundedCornerShape(6.dp)
      Box(
        modifier = Modifier
          .size(44.dp)
          .clip(coverShape)
          .border(0.5.dp, theme.textSecondary.copy(alpha = 0.18f), coverShape)
          .padding(0.5.dp)
      ) {
        GlideCover(
          model = song,
          circle = false,
          modifier = Modifier.fillMaxSize()
        )
      }

      Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
        modifier = Modifier
          .weight(1f)
          .padding(start = 14.dp, end = 8.dp)
      ) {
        // 标题略大且加粗一档，与副标题形成层级；当前播放行用强调色
        TextPrimary(
          text = song.showName,
          fontSize = 15.sp,
          fontWeight = FontWeight.Medium,
          color = if (playing && highlightPlaying) theme.highLightText() else theme.textPrimary
        )
        Spacer(modifier = Modifier.height(5.dp))
        // 本地与远程统一：专辑/歌手缺失时展示「未知专辑 / 未知艺术家」
        // 用「·」分隔比「-」更透气，长文本也更易读
        TextSecondary(
          String.format(
            "%s · %s",
            song.artist.ifBlank { stringResource(R.string.unknown_artist) },
            song.album.ifBlank { stringResource(R.string.unknown_album) }
          )
        )
      }

      // 收藏：两态都展示，点击即可加入 / 取消收藏（已收藏为实心红心，未收藏为空心）
      Box(
        modifier = Modifier
          .size(40.dp)
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(bounded = false, color = theme.ripple),
            onClick = { libraryVM.toggleFavorite(song.id) }
          ),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          painter = painterResource(
            if (favorite) R.drawable.ic_favorite_filled_24dp else R.drawable.ic_favorite_24dp
          ),
          contentDescription = stringResource(if (favorite) R.string.uncollect else R.string.collect),
          tint = if (favorite) FavoriteRed else theme.popupButton(),
          modifier = Modifier.size(18.dp)
        )
      }

      if (trailing != null) {
        trailing()
      } else {
        SongPopupButton(
          modifier = Modifier,
          song = song,
          parent = modelParent,
          enabled = popupEnabled
        )
      }
    }
  }

}
