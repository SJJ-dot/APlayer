package remix.myplayer.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.MediaStore.Audio
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import remix.myplayer.R
import remix.myplayer.data.db.room.entity.PlayList
import remix.myplayer.data.model.audio.APlayerModel
import remix.myplayer.data.model.audio.Album
import remix.myplayer.data.model.audio.Artist
import remix.myplayer.data.model.audio.Folder
import remix.myplayer.data.model.audio.Genre
import remix.myplayer.data.model.audio.Song
import remix.myplayer.data.prefs.SettingPrefs
import remix.myplayer.glide.UriFetcher
import remix.myplayer.helper.ItemsSorter
import remix.myplayer.helper.RemoteModelAggregator
import remix.myplayer.helper.SortOrder
import remix.myplayer.repo.AlbumRepository
import remix.myplayer.repo.ArtistRepository
import remix.myplayer.repo.FolderRepository
import remix.myplayer.repo.GenreRepository
import remix.myplayer.repo.HistoryRepository
import remix.myplayer.repo.PlayListRepository
import remix.myplayer.repo.SongRepository
import remix.myplayer.repo.UnifiedLibraryRepository
import remix.myplayer.repo.usecase.ExportPlayListUseCase
import remix.myplayer.repo.usecase.PlayFromUriUseCase
import remix.myplayer.service.MusicEventCallback
import remix.myplayer.service.MusicService
import remix.myplayer.ui.dialog.DialogState
import remix.myplayer.ui.nav.MessageNotifier
import remix.myplayer.util.PermissionUtil
import remix.myplayer.util.ext.checkWorkerThread
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
  private val savedStateHandle: SavedStateHandle,
  @param:ApplicationContext private val context: Context,
  private val songRepo: SongRepository,
  private val albumRepo: AlbumRepository,
  private val artistRepo: ArtistRepository,
  private val genreRepo: GenreRepository,
  private val playListRepo: PlayListRepository,
  private val folderRepo: FolderRepository,
  private val uriFetcher: UriFetcher,
  private val historyRepo: HistoryRepository,
  val settingPrefs: SettingPrefs,
  private val exportPlayListUseCase: ExportPlayListUseCase,
  private val playFromUriUseCase: PlayFromUriUseCase,
  private val unifiedLibraryRepository: UnifiedLibraryRepository
) : ViewModel(), MusicEventCallback {

  private var hasPermission = false

  private val _songs = MutableStateFlow<List<Song>>(emptyList())
  val songs: StateFlow<List<Song>> = _songs.asStateFlow()

  private val _albums = MutableStateFlow<List<Album>>(emptyList())
  val albums: StateFlow<List<Album>> = _albums.asStateFlow()

  private val _artists = MutableStateFlow<List<Artist>>(emptyList())
  val artists: StateFlow<List<Artist>> = _artists.asStateFlow()

  private val _genres = MutableStateFlow<List<Genre>>(emptyList())
  val genres: StateFlow<List<Genre>> = _genres.asStateFlow()

  val playLists = playListRepo.allPlayLists()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _folders = MutableStateFlow<List<Folder>>(emptyList())
  val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

  /** 远程曲库刷新中（供下拉刷新显示进度） */
  private val _refreshing = MutableStateFlow(false)
  val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

  val historySongs = historyRepo.allHistories().map { histories ->
    histories.mapNotNull { history ->
      val song = withContext(Dispatchers.IO) { songRepo.song(history.audio_id) }
      song?.let { it to history.play_count }
    }
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = emptyList()
  )

  private val _createPlaylistState = MutableStateFlow(CreatePlaylistState())
  val createPlaylistState = _createPlaylistState.asStateFlow()

  fun showCreatePlaylistDialog() {
    val defaultName = "${context.getString(R.string.local_list)}${playLists.value.size}"
    _createPlaylistState.update {
      it.dialogState.show()
      it.copy(name = defaultName)
    }
  }

  fun updateNewPlaylistName(name: String) {
    _createPlaylistState.update { it.copy(name = name) }
  }

  init {
    // load all media
    hasPermission = PermissionUtil.hasNecessaryPermission()
    if (hasPermission) {
      fetchMedia()
    }
  }

  fun insertPlayList(name: String, onSuccess: (Long) -> Unit) {
    viewModelScope.launch {
      if (playListRepo.checkPlayListExist(name)) {
        MessageNotifier.show(R.string.playlist_already_exist)
        return@launch
      }

      val id = playListRepo.insertPlayList(name)
      onSuccess(id)
    }
  }

  fun addSongsToPlayList(audioIds: List<Long>, playListName: String, createNew: Boolean = false) {
    viewModelScope.launch {
      try {
        if (createNew) {
          if (playListRepo.checkPlayListExist(playListName)) {
            MessageNotifier.show(R.string.playlist_already_exist)
            return@launch
          }

          playListRepo.insertPlayList(playListName)
        }

        val count = playListRepo.addSongsToPlayList(audioIds, playListName = playListName)
        MessageNotifier.show(R.string.add_song_playlist_success, count, playListName)
      } catch (ignore: Exception) {
        MessageNotifier.show(R.string.add_song_playlist_error)
      }
    }
  }

  suspend fun loadSongsByModels(models: List<APlayerModel>) = songRepo.getSongsByModels(models)

  fun loadSong(selection: String?, selectionValues: Array<String?>?, sortOrder: String? = null) =
    songRepo.getSongs(selection, selectionValues, sortOrder)

  fun loadLastAddedSongs() = songRepo.getLastAddedSongs()

  fun searchSong(key: String): List<Song> {
    checkWorkerThread()
    val likeKey = "%$key%"
    return songRepo.getSongs(
      "(" +
          Audio.Media.TITLE + " LIKE ? OR " +
          Audio.ArtistColumns.ARTIST + " LIKE ? OR " +
          Audio.AlbumColumns.ALBUM + " LIKE ? OR " +
          Audio.Media.DISPLAY_NAME + " LIKE ?" +
          ")",
      arrayOf(likeKey, likeKey, likeKey, likeKey),
      settingPrefs.songSortOrder
    )
  }

  fun updatePlayList(playList: PlayList) {
    viewModelScope.launch {
      try {
        val duplicate = playLists.value.find { it.name == playList.name && it.id != playList.id }
        if (duplicate != null) {
          MessageNotifier.show(R.string.playlist_already_exist)
          return@launch
        }

        playListRepo.updatePlayList(playList)
        uriFetcher.updatePlayListVersion()
        uriFetcher.clearAllCache()
        Glide.get(context).clearMemory()
        MessageNotifier.show(R.string.save_success)
      } catch (e: Exception) {
        MessageNotifier.show(R.string.save_error)
      }
    }
  }

  fun exportPlayListToFile(playList: PlayList?, uri: Uri) {
    viewModelScope.launch {
      exportPlayListUseCase(playList, uri)
    }
  }

  fun playFromUri(uri: Uri) {
    viewModelScope.launch {
      playFromUriUseCase(uri)
    }
  }

  fun fetchMedia(
    clear: Boolean = false,
    updateAlbumVersion: Boolean = false,
    updateArtistVersion: Boolean = false,
    updatePlayListVersion: Boolean = false,
    /** 是否在后台重新枚举远程音源；排序等只需重排的场景传 false，避免无谓加载 */
    refreshRemote: Boolean = true
  ) {
    viewModelScope.launch {
      if (clear) {
        if (updateAlbumVersion) {
          uriFetcher.updateAlbumVersion()
        } else if (updateArtistVersion) {
          uriFetcher.updateArtistVersion()
        } else if (updatePlayListVersion) {
          uriFetcher.updatePlayListVersion()
        } else {
          uriFetcher.updateAllVersion()
        }
        uriFetcher.clearAllCache()
        Glide.get(context).clearMemory()
      }

      // 1. 先加载并显示本地歌曲 + 缓存的远程歌曲（均不触网，启动即见）
      val localSongs = async(Dispatchers.IO) { unifiedLibraryRepository.localSongs() }.await()
      val cachedRemote = async(Dispatchers.IO) { unifiedLibraryRepository.remoteSongs() }.await()
      _songs.value = async(Dispatchers.IO) { mergeSongs(localSongs, cachedRemote) }.await()

      // 本地聚合视图（MediaStore）；本地音乐被禁用时不读取本地聚合
      val localMusicEnabled = settingPrefs.localMusicEnabled
      val localAlbums =
        if (localMusicEnabled) async(Dispatchers.IO) { albumRepo.allAlbums() }.await() else emptyList()
      val localArtists =
        if (localMusicEnabled) async(Dispatchers.IO) { artistRepo.allArtists() }.await() else emptyList()
      val localGenres =
        if (localMusicEnabled) async(Dispatchers.IO) { genreRepo.allGenres() }.await() else emptyList()
      applyRemoteAggregation(cachedRemote, localAlbums, localArtists, localGenres)

      // 2. 后台增量刷新远程歌曲（仅检查变化并补齐新文件/元数据），完成后合并刷新
      if (refreshRemote) {
        viewModelScope.launch(Dispatchers.IO) {
          _refreshing.value = true
          try {
            val remoteSongs = async(Dispatchers.IO) { unifiedLibraryRepository.refreshRemote() }.await()
            _songs.value = mergeSongs(_songs.value.filter { it.isLocal() }, remoteSongs)
            applyRemoteAggregation(remoteSongs, localAlbums, localArtists, localGenres)
          } finally {
            _refreshing.value = false
          }
        }
      }

      _folders.value =
        if (localMusicEnabled) async(Dispatchers.IO) { folderRepo.allFolders() }.await() else emptyList()
      Timber.v("songCount: ${_songs.value.size} albumCount: ${_albums.value.size} artistCount: ${_artists.value.size} genreCount: ${_genres.value.size} folderCount: ${_folders.value.size}")
    }
  }

  /**
   * 合并本地与远程歌曲并**整体排序**（不区分来源）：
   * 合并后全量在内存中按当前排序方式统一排序，因此本地与远程会按同一规则混排，
   * 而不是本地在前、远程追加在末尾。调用方需在 IO 线程执行（全量排序有一定开销）。
   */
  private fun mergeSongs(local: List<Song>, remote: List<Song>): List<Song> =
    sortSongs((local + remote).distinctBy { if (it.isLocal()) it.data else it.id })

  /** 按当前排序方式整体排序（本地与远程同一规则） */
  private fun sortSongs(songs: List<Song>): List<Song> =
    when (val sortOrder = settingPrefs.songSortOrder) {
      // 以下几种 ItemsSorter 不排序（原本依赖数据库查询顺序），这里给出统一规则
      SortOrder.DATE -> songs.sortedBy { it.dateModified }
      SortOrder.DATE_DESC -> songs.sortedByDescending { it.dateModified }
      SortOrder.TRACK_NUMBER -> songs.sortedBy { trackNumberOf(it) }
      else -> ItemsSorter.sortedSongs(songs, sortOrder)
    }

  /**
   * 切换排序方式时专用：只把已加载的歌曲按新排序方式重排，
   * **不重新查询 MediaStore、不重新枚举远程音源**（避免排序时反复加载/转圈）。
   */
  fun resortSongs() = viewModelScope.launch {
    val current = _songs.value
    if (current.size > 1) {
      _songs.value = withContext(Dispatchers.IO) { sortSongs(current) }
    }
  }

  /** 音轨号："3/12" → 3；无轨号排到最后 */
  private fun trackNumberOf(song: Song): Int =
    song.track?.substringBefore('/')?.trim()?.toIntOrNull() ?: Int.MAX_VALUE

  /**
   * 把远程歌曲聚合进专辑 / 歌手 / 流派视图：远程按名称聚合（稳定负 id），
   * 与本地结果合并后按当前排序方式统一排序。
   */
  private fun applyRemoteAggregation(
    remoteSongs: List<Song>,
    localAlbums: List<Album>,
    localArtists: List<Artist>,
    localGenres: List<Genre>
  ) {
    _albums.value = ItemsSorter.sortedAlbums(
      localAlbums + RemoteModelAggregator.albums(remoteSongs), settingPrefs.albumSortOrder
    )
    _artists.value = ItemsSorter.sortedArtists(
      localArtists + RemoteModelAggregator.artists(remoteSongs), settingPrefs.artistSortOrder
    )
    _genres.value = ItemsSorter.sortedGenres(
      localGenres + RemoteModelAggregator.genres(remoteSongs), settingPrefs.genreSortOrder
    )
  }

  fun clearHistory() = viewModelScope.launch {
    historyRepo.clear()
  }

  override fun onMediaStoreChanged() {
    // 本地音乐被禁用或关闭「自动扫描」时，不再自动刷新曲库（手动扫描会显式刷新）
    if (hasPermission && settingPrefs.autoScanLocal && settingPrefs.localMusicEnabled) {
      fetchMedia()
    }
  }

  override fun onPermissionChanged(has: Boolean) {
    if (has && !hasPermission) {
      fetchMedia()
    }
    hasPermission = has
  }

  override fun onPlayListChanged(name: String) {
  }

  override fun onServiceConnected(service: MusicService) {
  }

  override fun onServiceDisConnected() {
  }

  override fun onTagChanged(
    oldSong: Song?, newSong: Song
  ) {
    fetchMedia(true, updateAlbumVersion = true, updatePlayListVersion = true)
  }
}

data class CreatePlaylistState(
  val dialogState: DialogState = DialogState(),
  val name: String = ""
)
