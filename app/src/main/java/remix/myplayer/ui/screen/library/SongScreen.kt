package remix.myplayer.ui.screen.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import remix.myplayer.R
import remix.myplayer.service.Command
import remix.myplayer.service.MusicService
import remix.myplayer.service.MusicServiceRemote.setPlayQueue
import remix.myplayer.ui.theme.LocalTheme
import remix.myplayer.ui.widget.library.SongListHeader
import remix.myplayer.ui.widget.library.list.ListSong
import remix.myplayer.util.MusicUtil
import remix.myplayer.util.ext.clickableWithoutRipple
import remix.myplayer.util.ext.verticalScrollbar
import remix.myplayer.data.model.audio.Song
import remix.myplayer.data.model.misc.Library
import remix.myplayer.viewmodel.MultiSelectState
import remix.myplayer.viewmodel.libraryViewModel
import remix.myplayer.viewmodel.mainViewModel
import remix.myplayer.viewmodel.playbackViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongScreen(
  scrollToCurrentEvent: SharedFlow<Unit>? = null,
  sourceFilter: ((Song) -> Boolean)? = null
) {
  val libraryVM = libraryViewModel
  val mainVM = mainViewModel
  val scope = rememberCoroutineScope()

  val playbackState by playbackViewModel.playbackUiState.collectAsStateWithLifecycle()
  val multiSelectState by mainVM.multiSelectState.collectAsStateWithLifecycle()
  val listState = rememberLazyListState()
  val songs by libraryVM.songs.collectAsStateWithLifecycle()
  val refreshing by libraryVM.refreshing.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val popupEnabled = !multiSelectState.isShowInLibrary()

  val displaySongs = remember(songs, sourceFilter) {
    sourceFilter?.let { songs.filter(it) } ?: songs
  }

  // 当前播放歌曲在本页列表中的位置（-1 表示不在本页）
  val playingIndex = displaySongs.indexOfFirst { it.id == playbackState.song.id }

  // 排序方式参与 item key：切换排序时让 LazyColumn 按位置保持，而不是按 key 追踪旧位置而自动滚动
  val sortOrderKey = libraryVM.settingPrefs.songSortOrder

  LaunchedEffect(scrollToCurrentEvent) {
    scrollToCurrentEvent?.collect {
      val index = libraryVM.songs.value.indexOfFirst { it.id == playbackState.song.id }
      if (index != -1) {
        listState.scrollToItem(index)
      }
    }
  }

  Column {
    if (displaySongs.isNotEmpty()) {
      SongListHeader(displaySongs)
    }

    val selectedIds by remember {
      derivedStateOf {
        multiSelectState.selectedModels(MultiSelectState.Where.Song)
      }
    }

    Box(modifier = Modifier.weight(1f)) {
      PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
          // 下拉刷新：重新加载曲库并重新枚举远程音源（弱网下可手动重试）
          libraryVM.fetchMedia()
        },
        modifier = Modifier.fillMaxSize()
      ) {
        LazyColumn(
          state = listState,
          modifier = Modifier
            .fillMaxSize()
            .verticalScrollbar(listState)
        ) {
          itemsIndexed(
            displaySongs,
            key = { _, song -> "$sortOrderKey#${song.id}" }
          ) { pos, song ->
            val selected = selectedIds.contains(song.getKey())
            val isPlayingSong = playbackState.song.id == song.id

            ListSong(
              modifier = Modifier.height(64.dp),
              song = song,
              modelParent = song,
              selected = selected,
              playing = isPlayingSong,
              popupEnabled = popupEnabled,
              onClickSong = {
                if (displaySongs.isEmpty()) {
                  return@ListSong
                }

                if (multiSelectState.where == MultiSelectState.Where.Song) {
                  mainVM.updateMultiSelectModel(song)
                  return@ListSong
                }

                setPlayQueue(
                  displaySongs, MusicUtil.makeCmdIntent(Command.PLAY_AT)
                    .putExtra(MusicService.EXTRA_POSITION, pos)
                )
              },
              onLongClickSong = {
                mainVM.showMultiSelect(context, MultiSelectState.Where.Song, song)
              })
          }
        }
      }

      // 定位按钮：滚动到当前正在播放的歌曲（仅当它在本页列表中时显示）
      if (playingIndex >= 0) {
        Icon(
          modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 16.dp, bottom = 16.dp)
            .size(44.dp)
            .background(LocalTheme.current.dialogBackground, CircleShape)
            .clickableWithoutRipple {
              scope.launch {
                listState.animateScrollToItem(playingIndex)
              }
            }
            .padding(10.dp),
          painter = painterResource(R.drawable.ic_my_location_24dp),
          contentDescription = "LocatePlayingSong",
          tint = LocalTheme.current.primary
        )
      }
    }
  }
}

/**
 * 判断歌曲归属的来源分类，用于按 本地 / WebDAV / SMB 筛选展示。
 * SMB 远程歌的 data 以 "smb://" 开头（与 FetchMetaDataUseCase 判定一致），其余远程歌视为 WebDAV。
 */
private fun Song.matchesSource(tag: Int): Boolean = when (tag) {
  Library.TAG_LOCAL -> isLocal()
  Library.TAG_WEBDAV -> this is Song.Remote && !data.startsWith("smb://")
  Library.TAG_SMB -> this is Song.Remote && data.startsWith("smb://")
  else -> true
}

@Composable
fun LocalSongScreen() = SongScreen(sourceFilter = { it.matchesSource(Library.TAG_LOCAL) })

@Composable
fun WebDavSongScreen() = SongScreen(sourceFilter = { it.matchesSource(Library.TAG_WEBDAV) })

@Composable
fun SmbSongScreen() = SongScreen(sourceFilter = { it.matchesSource(Library.TAG_SMB) })
