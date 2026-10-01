package remix.myplayer.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import remix.myplayer.R
import remix.myplayer.data.db.room.entity.WebDav
import remix.myplayer.data.model.audio.Song
import remix.myplayer.repo.WebDavRepository
import remix.myplayer.repo.source.RemoteSongLookup
import remix.myplayer.repo.usecase.FetchMetaDataUseCase
import remix.myplayer.service.MusicServiceRemote
import remix.myplayer.util.WebDavSardineFactory
import remix.myplayer.ui.dialog.DialogState
import remix.myplayer.ui.dialog.runWithLoading
import remix.myplayer.ui.nav.MessageNotifier
import remix.myplayer.ui.state.DataUiState
import remix.myplayer.util.ext.isAudio
import remix.myplayer.util.ext.updateIf
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class WebDavViewModel @Inject constructor(
  private val webDavRepository: WebDavRepository,
  private val remoteSongLookup: RemoteSongLookup,
  private val fetchMetaDataUseCase: FetchMetaDataUseCase
) : ViewModel() {

  private val _webDavList = MutableStateFlow<List<WebDav>>(emptyList())
  val webDavList: StateFlow<List<WebDav>> = _webDavList.asStateFlow()

  private val _webDavResState =
    MutableStateFlow<DataUiState<List<DavResource>>>(DataUiState.Loading())
  val webDavResState: StateFlow<DataUiState<List<DavResource>>> = _webDavResState.asStateFlow()

  init {
    viewModelScope.launch {
      webDavRepository.allWebDav().collect {
        _webDavList.value = it
      }
    }
  }

  fun loadDavRes(sardine: OkHttpSardine, url: String) {
    _webDavResState.value = DataUiState.Loading()
    viewModelScope.launch {
      val resources = try {
        withContext(Dispatchers.IO) {
          sardine.list(url)
        }
      } catch (e: Exception) {
        _webDavResState.value = DataUiState.Error(e)
        return@launch
      }
      _webDavResState.value =
        DataUiState.Success(resources.drop(1).filter { it.isAudio() || it.isDirectory })
    }
  }

  /** 离开页面时重置加载状态，避免下次进入读到残留 Error 而立即退出 */
  fun clearResState() {
    _webDavResState.value = DataUiState.Loading()
  }

  fun deleteWebDav(webDav: WebDav) = viewModelScope.launch {
    webDavRepository.delete(webDav)
    // 同步清理该源的缓存歌曲与内存解析表，并从播放队列移除
    val removedIds = remoteSongLookup.clearSource("webdav:${webDav.server}")
    if (removedIds.isNotEmpty()) {
      MusicServiceRemote.removeFromQueue(removedIds)
    }
  }

  /**
   * 启用/禁用音源。禁用时清掉该源的缓存歌曲、内存解析表与播放队列。
   *
   * 为 suspend 方法：调用方需在本方法**返回后**再刷新曲库，否则清理与刷新并发执行时，
   * 曲库会先读到尚未清理的旧缓存（表现为禁用后歌曲仍在列表中）。
   */
  suspend fun setEnabled(webDav: WebDav, enabled: Boolean) {
    // 不要就地修改传入实例：它正是列表 UI 展示的那个对象，改了会让新旧列表 equals 相同、
    // Compose 判定无变化而不重组（表现为开关不变，退出重进才更新）。写库一律用 copy 生成新实例。
    webDavRepository.insertOrReplace(webDav.copy(enabled = enabled).also { it.id = webDav.id })
    if (!enabled) {
      val removedIds = remoteSongLookup.clearSource("webdav:${webDav.server}")
      if (removedIds.isNotEmpty()) {
        MusicServiceRemote.removeFromQueue(removedIds)
      }
    }
  }

  /** 一次性查询（不依赖列表 Flow 的刷新时机），供导航后立即按 id 恢复实体 */
  suspend fun getWebDavById(id: Int): WebDav? = webDavRepository.byId(id)

  fun updateLastUrl(webDav: WebDav, newUrl: String) = viewModelScope.launch {
    if (webDav.lastUrl == newUrl) {
      return@launch
    }
    webDav.lastUrl = newUrl
    webDavRepository.insertOrReplace(webDav)
  }

  /**
   * 保存导入根目录，并把浏览位置重置到该目录。
   *
   * 为 suspend 方法：调用方需在本方法**返回后**再刷新曲库，确保后台枚举读到的是新 rootDir，
   * 否则会按旧目录做 diff，导致新目录下的歌曲无法立即导入。
   */
  suspend fun updateRootDir(webDav: WebDav, rootUrl: String) {
    webDavRepository.insertOrReplace(
      webDav.copy(rootDir = rootUrl, lastUrl = rootUrl).also { it.id = webDav.id }
    )
  }

  suspend fun saveWebDav(webdav: WebDav): Boolean {
    // 兜底清理，防止输入框带入不可见的换行/空格导致 401
    webdav.account = webdav.account.trim()
    webdav.pwd = webdav.pwd.trim()
    webdav.server = webdav.server.trim().removeSuffix("/")
    val sardine = WebDavSardineFactory.create(webdav.account, webdav.pwd)
    return try {
      val davResources = withContext(Dispatchers.IO) {
        sardine.list(webdav.server)
      }
      if (davResources.isNotEmpty()) {
        // Room 不会把自增主键写回实体，需手动回填，否则后续按 id 导航/查询会拿到 0
        val rowId = webDavRepository.insertOrReplace(webdav)
        if (webdav.id == 0 && rowId > 0) {
          webdav.id = rowId.toInt()
        }
        true
      } else {
        MessageNotifier.show(R.string.add_error)
        false
      }
    } catch (e: Exception) {
      Timber.e(e)
      MessageNotifier.show(e.localizedMessage ?: "Save failed")
      false
    }
  }

  fun insertOrReplaceWebDav(webdav: WebDav) = viewModelScope.launch {
    saveWebDav(webdav)
  }

  private val _addWebDavState = MutableStateFlow(AddWebDavState(DialogState()))
  val addWebDavState = _addWebDavState.asStateFlow()

  fun updateAddWebDavState(
    alias: String? = null,
    account: String? = null,
    pwd: String? = null,
    server: String? = null
  ) {
    _addWebDavState.update {
      it.copy(
        alias = alias ?: it.alias,
        account = account ?: it.account,
        pwd = pwd ?: it.pwd,
        server = server ?: it.server
      )
    }
  }

  fun showAddWebDavDialog(editWebDav: WebDav? = null) {
    _addWebDavState.updateIf(
      condition = { !it.dialogState.isOpen },
      transform = {
        it.dialogState.show()
        it.copy(
          alias = editWebDav?.alias ?: it.alias,
          account = editWebDav?.account ?: it.account,
          pwd = editWebDav?.pwd ?: it.pwd,
          server = editWebDav?.server ?: it.server,
          editWebDav = editWebDav
        )
      }
    )
  }

  suspend fun fetchMeta(song: Song.Remote) = fetchMetaDataUseCase(song)
}

@Stable
data class AddWebDavState(
  val dialogState: DialogState,
  val editWebDav: WebDav? = null,
  val alias: String = "",
  val account: String = "",
  val pwd: String = "",
  val server: String = ""
)
