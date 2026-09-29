package remix.myplayer.ui.screen.webdav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import remix.myplayer.R
import remix.myplayer.data.db.room.entity.WebDav
import remix.myplayer.data.model.audio.Song
import remix.myplayer.service.Command
import remix.myplayer.service.MusicService
import remix.myplayer.service.MusicServiceRemote
import remix.myplayer.ui.dialog.NormalDialog
import remix.myplayer.ui.dialog.rememberDialogState
import remix.myplayer.ui.dialog.runWithLoading
import remix.myplayer.ui.nav.LocalNavController
import remix.myplayer.ui.nav.MessageNotifier
import remix.myplayer.ui.state.DataUiState
import remix.myplayer.ui.theme.LocalTheme
import remix.myplayer.ui.theme.icon
import remix.myplayer.ui.widget.app.BottomBar
import remix.myplayer.ui.widget.common.AppBarAction
import remix.myplayer.ui.widget.common.CommonAppBar
import remix.myplayer.ui.widget.common.PopupButton
import remix.myplayer.ui.widget.common.TextPrimary
import remix.myplayer.ui.widget.common.TextSecondary
import remix.myplayer.util.MusicUtil
import remix.myplayer.util.Util
import remix.myplayer.util.WebDavSardineFactory
import remix.myplayer.util.ext.clickWithRipple
import remix.myplayer.util.ext.clickableWithoutRipple
import remix.myplayer.util.ext.isAudio
import remix.myplayer.viewmodel.libraryViewModel
import remix.myplayer.viewmodel.playbackViewModel
import remix.myplayer.viewmodel.settingViewModel
import remix.myplayer.viewmodel.webDavViewModel
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavDetailScreen(webDav: WebDav, pickMode: Boolean = false) {
  val nav = LocalNavController.current
  val webDavVM = webDavViewModel
  val playbackVM = playbackViewModel
  val settingVM = settingViewModel
  val libraryVM = libraryViewModel
  val scope = rememberCoroutineScope()
  val resourceState by webDavVM.webDavResState.collectAsStateWithLifecycle()

  val pathStack = rememberSaveable(
    saver = listSaver(
      save = { it.toList() },
      restore = { it.toMutableStateList() })
  ) {
    webDav.buildPathStack(webDav.lastUrl).toMutableStateList()
  }
  val currentUrl = pathStack.last()

  var davResources by remember {
    mutableStateOf<List<DavResource>>(emptyList())
  }
  var refreshTrigger by remember {
    mutableIntStateOf(0)
  }
  // 下拉刷新中
  var refreshing by remember {
    mutableStateOf(false)
  }

  // 删除二次确认
  val deleteDialogState = rememberDialogState()
  var pendingDelete by remember { mutableStateOf<DavResource?>(null) }

  val sardine = remember(webDav.id, webDav.account, webDav.pwd) {
    WebDavSardineFactory.create(webDav.account, webDav.pwd)
  }

  // 离开页面时重置加载状态，避免下次进入读到残留 Error 而立即退出（白屏）
  DisposableEffect(webDav.id) {
    onDispose {
      webDavVM.clearResState()
    }
  }

  fun handleBack() {
    if (pathStack.size <= 1) {
      nav.popBackStack()
      return
    }
    pathStack.removeAt(pathStack.lastIndex)
  }

  BackHandler {
    handleBack()
  }

  Scaffold(
    topBar = {
      CommonAppBar(
        title = webDav.alias,
        onBack = {
          handleBack()
        },
        actions = listOf(AppBarAction(R.drawable.ic_close_white_24dp, "WebDetailClose") {
          nav.popBackStack()
        })
      )
    },
    containerColor = LocalTheme.current.mainBackground
  ) { contentPadding ->
    val showLoading = resourceState is DataUiState.Loading

    LaunchedEffect(resourceState) {
      when (resourceState) {
        is DataUiState.Success -> {
          refreshing = false
          davResources = resourceState.get()
          webDavVM.updateLastUrl(webDav, currentUrl)
        }

        is DataUiState.Error -> {
          refreshing = false
          val ex = (resourceState as DataUiState.Error).throwable
          if (ex is SardineException && ex.statusCode == 404) {
            // 路径确实不存在：回退到根目录或退出
            if (pathStack.size <= 1) {
              nav.popBackStack()
              MessageNotifier.show(R.string.load_failed)
            } else {
              pathStack.removeRange(1, pathStack.size)
              MessageNotifier.show(R.string.file_not_exist)
            }
          } else {
            // 弱网 / 超时 / 认证失败等：留在当前页面，下拉可重试，不再直接退出
            MessageNotifier.show(R.string.load_failed_retry)
          }
        }

        else -> {}
      }
    }

    Column(modifier = Modifier.padding(contentPadding)) {
      Box(modifier = Modifier.weight(1f)) {
        PullToRefreshBox(
          isRefreshing = refreshing,
          onRefresh = {
            refreshing = true
            refreshTrigger++
          },
          modifier = Modifier.fillMaxSize()
        ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
          items(davResources, key = { it.path }) { resource ->
            WebDavDetailItem(
              resource,
              onClick = {
                if (showLoading) return@WebDavDetailItem

                if (resource.isDirectory) {
                  // 进入下级目录
                  pathStack.add(webDav.generateUrl(resource.path))
                } else if (!pickMode) {
                  // 选目录模式下不播放；过滤列表内所有音乐并设置为播放列表
                  if (davResources.isEmpty()) {
                    return@WebDavDetailItem
                  }
                  var select: Song.Remote? = null
                  val remotes = davResources
                    .filter { it.isAudio() }
                    .map {
                      val remote = Song.Remote(
                        title = it.name.substringBeforeLast('.'),
                        data = webDav.generateUrl(it.path),
                        size = it.contentLength,
                        dateModified = it.creation?.time ?: 0,
                        account = webDav.account,
                        pwd = webDav.pwd
                      )
                      if (it == resource) {
                        select = remote
                      }
                      remote
                    }
                  MusicServiceRemote.setPlayQueue(
                    remotes,
                    MusicUtil.makeCmdIntent(Command.PLAY_AT)
                      .putExtra(MusicService.EXTRA_POSITION, remotes.indexOfFirst {
                        it.data == select?.data
                      })
                  )
                }
              },
              onMenuClick = {
                val song = Song.Remote(
                  title = resource.name.substringBeforeLast('.'),
                  data = webDav.generateUrl(resource.path),
                  size = resource.contentLength,
                  dateModified = resource.creation?.time ?: 0,
                  account = webDav.account,
                  pwd = webDav.pwd
                )
                when (it) {
                  R.string.add_to_next_song -> {
                    Util.sendLocalBroadcast(
                      MusicUtil.makeCmdIntent(Command.ADD_TO_NEXT_SONG)
                        .putExtra(MusicService.EXTRA_SONG, song)
                    )
                  }

                  R.string.add_to_play_queue -> {
                    playbackVM.insertToQueue(listOf(song))
                  }

                  R.string.song_detail -> {
                    scope.runWithLoading {
                      webDavVM.fetchMeta(song)
                      settingVM.showSongDetailDialog(song)
                    }
                  }

                  R.string.delete -> {
                    pendingDelete = resource
                    deleteDialogState.show()
                  }
                }
              })
          }
        }

        }
        if (showLoading) {
          LinearProgressIndicator(
            modifier = Modifier
              .fillMaxWidth()
              .align(Alignment.TopCenter),
            color = LocalTheme.current.primary
          )
        }
      }
      if (pickMode) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .height(44.dp)
            .background(color = LocalTheme.current.primary, shape = RoundedCornerShape(22.dp))
            .clickableWithoutRipple {
              webDavVM.updateRootDir(webDav, currentUrl)
              MessageNotifier.show(R.string.music_folder_set)
              // 立即按新目录刷新远程曲库
              libraryVM.fetchMedia()
              nav.popBackStack()
            },
          contentAlignment = Alignment.Center
        ) {
          Text(
            stringResource(R.string.use_this_folder),
            color = Color.White
          )
        }
      } else {
        BottomBar()
      }
    }
  }

  NormalDialog(
    dialogState = deleteDialogState,
    title = stringResource(R.string.delete),
    content = pendingDelete?.name ?: "",
    onPositive = {
      val target = pendingDelete ?: return@NormalDialog
      pendingDelete = null
      scope.runWithLoading {
        withContext(Dispatchers.IO) {
          sardine.delete(webDav.generateUrl(target.path))
        }
        // 同步刷新曲库（会清理该文件的缓存）
        libraryVM.fetchMedia()
        refreshTrigger++
      }
    }
  )

  LaunchedEffect(currentUrl, refreshTrigger) {
    webDavVM.loadDavRes(sardine, currentUrl)
  }
}

@Composable
private fun WebDavDetailItem(
  davResource: DavResource,
  onClick: () -> Unit,
  onMenuClick: (Int) -> Unit
) {
  val theme = LocalTheme.current

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(64.dp)
      .clickWithRipple(false) {
        onClick()
      },
    verticalAlignment = Alignment.CenterVertically
  ) {
    val isAudio = davResource.isAudio()
    val icon = if (davResource.isDirectory) {
      R.drawable.ic_folder_24dp
    } else if (isAudio) {
      R.drawable.ic_audio_file_24dp
    } else {
      R.drawable.ic_lab_profile_24dp
    }

    Icon(
      modifier = Modifier
        .padding(start = 12.dp),
      painter = painterResource(icon),
      contentDescription = "IconWebDavDetailItem",
      tint = theme.icon()
    )

    Column(
      modifier = Modifier
        .padding(horizontal = 12.dp)
        .weight(1f),
      horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.Center
    ) {
      TextPrimary(davResource.name)
      Spacer(Modifier.height(4.dp))
      TextSecondary(davResource.path)
    }

    val list = arrayListOf(R.string.delete)
    if (davResource.isAudio()) {
      list.addAll(
        0,
        listOf(
          R.string.add_to_next_song,
          R.string.add_to_play_queue,
          R.string.song_detail,
        )
      )
    }
    PopupButton(list, contentDescription = "WebDavDetailPopupButton", onMenuClick = onMenuClick)
  }
}
