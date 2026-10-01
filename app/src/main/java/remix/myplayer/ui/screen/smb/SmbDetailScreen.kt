package remix.myplayer.ui.screen.smb

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
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
import remix.myplayer.R
import remix.myplayer.data.db.room.entity.Smb
import remix.myplayer.data.model.audio.Song
import remix.myplayer.data.model.smb.SmbException
import remix.myplayer.data.model.smb.SmbFile
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
import remix.myplayer.util.ext.clickWithRipple
import remix.myplayer.util.ext.clickableWithoutRipple
import remix.myplayer.viewmodel.libraryViewModel
import remix.myplayer.viewmodel.playbackViewModel
import remix.myplayer.viewmodel.settingViewModel
import remix.myplayer.viewmodel.smbViewModel
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmbDetailScreen(smb: Smb, pickMode: Boolean = false) {
  val nav = LocalNavController.current
  val smbVM = smbViewModel
  val playbackVM = playbackViewModel
  val settingVM = settingViewModel
  val libraryVM = libraryViewModel
  val scope = rememberCoroutineScope()
  val resourceState by smbVM.smbResState.collectAsStateWithLifecycle()

  val pathStack = rememberSaveable(
    saver = listSaver(
      save = { it.toList() },
      restore = { it.toMutableStateList() })
  ) {
    smb.buildPathStack(smb.lastUrl).toMutableStateList()
  }
  val currentUrl = pathStack.last()

  var smbFiles by remember {
    mutableStateOf<List<SmbFile>>(emptyList())
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
  var pendingDelete by remember { mutableStateOf<SmbFile?>(null) }

  // 离开页面时重置加载状态，避免下次进入读到残留 Error 而立即退出（白屏）
  DisposableEffect(smb.id) {
    onDispose {
      smbVM.clearResState()
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
    contentWindowInsets = WindowInsets.systemBars,
    topBar = {
      CommonAppBar(
        title = smb.alias,
        onBack = {
          handleBack()
        },
        actions = listOf(AppBarAction(R.drawable.ic_close_white_24dp, "SmbDetailClose") {
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
          smbFiles = resourceState.get()
          smbVM.updateLastUrl(smb, currentUrl)
        }

        is DataUiState.Error -> {
          refreshing = false
          val ex = (resourceState as DataUiState.Error).throwable
          if (ex is SmbException && ex.isNotFound) {
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
          items(smbFiles, key = { it.path }) { resource ->
            SmbDetailItem(
              resource,
              onClick = {
                if (showLoading) return@SmbDetailItem

                if (resource.isDirectory) {
                  // Enter directory
                  val nextPath =
                    smb.getRoot().removeSuffix("/") + "/" + resource.path.trimStart('/')
                  Timber.v("nextPath: $nextPath")
                  pathStack.add(nextPath)
                } else if (!pickMode) {
                  // 选目录模式下不播放；Filter music and play
                  if (smbFiles.isEmpty()) {
                    return@SmbDetailItem
                  }

                  var select: Song.Remote? = null
                  val remotes = smbFiles
                    .filter { it.isAudio }
                    .map {
                      val uriStr = smb.generateUri(it.path)

                      val remote = Song.Remote(
                        title = it.name.substringBeforeLast('.'),
                        data = uriStr,
                        size = it.size,
                        dateModified = it.lastModified,
                        account = smb.account,
                        pwd = smb.pwd
                      )
                      if (it == resource) {
                        select = remote
                      }
                      remote
                    }

                  if (remotes.isNotEmpty()) {
                    MusicServiceRemote.setPlayQueue(
                      remotes,
                      MusicUtil.makeCmdIntent(Command.PLAY_AT)
                        .putExtra(MusicService.EXTRA_POSITION, remotes.indexOfFirst {
                          it.data == select?.data
                        })
                    )
                  }
                }
              },
              onMenuClick = {
                val uriStr = smb.generateUri(resource.path)

                val song = Song.Remote(
                  title = resource.name.substringBeforeLast('.'),
                  data = uriStr,
                  size = resource.size,
                  dateModified = resource.lastModified,
                  account = smb.account,
                  pwd = smb.pwd
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
                      smbVM.fetchMeta(song)
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
              smbVM.updateRootDir(smb, smb.getRelativePath(currentUrl), currentUrl)
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
        if (smbVM.deleteRemoteFile(smb, target.path)) {
          MessageNotifier.show(R.string.delete_success)
          // 同步刷新曲库（会清理该文件的缓存）
          libraryVM.fetchMedia()
          refreshTrigger++
        }
      }
    }
  )

  LaunchedEffect(currentUrl, refreshTrigger) {
    smbVM.loadSmbRes(smb, currentUrl)
  }
}

@Composable
private fun SmbDetailItem(
  smbFile: SmbFile,
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
    val isAudio = smbFile.isAudio
    val icon = if (smbFile.isDirectory) {
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
      contentDescription = "IconSmbDetailItem",
      tint = theme.icon()
    )

    Column(
      modifier = Modifier
        .padding(horizontal = 12.dp)
        .weight(1f),
      horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.Center
    ) {
      TextPrimary(smbFile.name)
      Spacer(Modifier.height(4.dp))
      TextSecondary(smbFile.path)
    }

    val list = arrayListOf<Int>()
    if (smbFile.isDirectory) {
      list.add(R.string.delete)
    }

    if (isAudio) {
      list.addAll(
        0,
        listOf(
          R.string.add_to_next_song,
          R.string.add_to_play_queue,
          R.string.song_detail,
          R.string.delete
        )
      )
    }
    PopupButton(list, contentDescription = "SmbDetailPopupButton", onMenuClick = onMenuClick)
  }
}
