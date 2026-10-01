package remix.myplayer.viewmodel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import remix.myplayer.util.ext.activityViewModel
import remix.myplayer.viewmodel.settings.SettingViewModel

val LocalLibraryViewModel = compositionLocalOf<LibraryViewModel> {
  error("LibraryViewModel not provided")
}
val LocalSettingViewModel = compositionLocalOf<SettingViewModel> {
  error("SettingViewModel not provided")
}
val LocalTagEditViewModel = compositionLocalOf<TagEditViewModel> {
  error("TagEditViewModel not provided")
}
val LocalMainViewModel = compositionLocalOf<MainViewModel> {
  error("MainViewModel not provided")
}
val LocalTimerViewModel = compositionLocalOf<TimerViewModel> {
  error("TimerViewModel not provided")
}
val LocalWebDavViewModel = compositionLocalOf<WebDavViewModel> {
  error("WebDavViewModel not provided")
}
val LocalSmbViewModel = compositionLocalOf<SmbViewModel> {
  error("SmbViewModel not provided")
}
val LocalPlaybackViewModel = compositionLocalOf<PlaybackViewModel> {
  error("PlaybackViewModel not provided")
}

/**
 * 收藏歌曲 id 集合。
 *
 * 由 [ProvideViewModels] 在根部**统一订阅一次**后经此提供，歌曲列表与播放队列的 item
 * 直接读取即可展示收藏状态，避免每个 item 各自订阅一份收藏数据。
 */
val LocalFavoriteSongIds = compositionLocalOf { emptySet<Long>() }


@Composable
fun ProvideViewModels(content: @Composable () -> Unit) {
  CompositionLocalProvider(
    LocalLibraryViewModel provides activityViewModel(),
    LocalSettingViewModel provides activityViewModel(),
    LocalTagEditViewModel provides activityViewModel(),
    LocalMainViewModel provides activityViewModel(),
    LocalTimerViewModel provides activityViewModel(),
    LocalWebDavViewModel provides activityViewModel(),
    LocalSmbViewModel provides activityViewModel(),
    LocalPlaybackViewModel provides activityViewModel()
  ) {
    // 收藏状态是全局只读数据：在根部订阅一次即可，列表项直接读取 [LocalFavoriteSongIds]
    val favoriteSongIds by libraryViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalFavoriteSongIds provides favoriteSongIds) {
      content()
    }
  }
}

val mainViewModel: MainViewModel
  @Composable
  @ReadOnlyComposable
  get() = LocalMainViewModel.current

val libraryViewModel: LibraryViewModel
  @Composable
  @ReadOnlyComposable
  get() = LocalLibraryViewModel.current

val settingViewModel: SettingViewModel
  @Composable
  @ReadOnlyComposable
  get() = LocalSettingViewModel.current

val tagEditViewModel: TagEditViewModel
  @Composable
  @ReadOnlyComposable
  get() = LocalTagEditViewModel.current

val timerViewModel: TimerViewModel
  @Composable
  @ReadOnlyComposable
  get() = LocalTimerViewModel.current

val webDavViewModel: WebDavViewModel
  @Composable
  @ReadOnlyComposable
  get() = LocalWebDavViewModel.current

val playbackViewModel: PlaybackViewModel
  @Composable
  @ReadOnlyComposable
  get() = LocalPlaybackViewModel.current

val smbViewModel: SmbViewModel
  @Composable
  @ReadOnlyComposable
  get() = LocalSmbViewModel.current
