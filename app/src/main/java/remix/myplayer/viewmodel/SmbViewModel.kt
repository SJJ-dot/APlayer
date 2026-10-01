package remix.myplayer.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import remix.myplayer.data.db.room.entity.Smb
import remix.myplayer.data.model.audio.Song
import remix.myplayer.data.model.smb.SmbClientDelegateProvider
import remix.myplayer.data.model.smb.SmbFile
import remix.myplayer.misc.manager.DynamicModuleManager
import remix.myplayer.misc.manager.DynamicModuleStatus
import remix.myplayer.repo.SmbRepository
import remix.myplayer.repo.source.RemoteSongLookup
import remix.myplayer.repo.usecase.FetchMetaDataUseCase
import remix.myplayer.service.MusicServiceRemote
import remix.myplayer.ui.dialog.DialogState
import remix.myplayer.ui.dialog.runWithLoading
import remix.myplayer.ui.nav.MessageNotifier
import remix.myplayer.ui.state.DataUiState
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SmbViewModel @Inject constructor(
  private val smbRepository: SmbRepository,
  private val remoteSongLookup: RemoteSongLookup,
  private val fetchMetaDataUseCase: FetchMetaDataUseCase,
  private val delegateProvider: SmbClientDelegateProvider,
  private val dynamicModuleManager: DynamicModuleManager
) : ViewModel() {

  val supportSmb get() = dynamicModuleManager.isModuleSupport("feature_smb")

  val isSmbModuleInstalled get() = dynamicModuleManager.isModuleInstalled("feature_smb")

  fun installSmbModule() = dynamicModuleManager.installModule("feature_smb")

  private val _moduleInstallStatus = MutableStateFlow<DynamicModuleStatus?>(null)
  val moduleInstallStatus = _moduleInstallStatus.asStateFlow()

  fun startSmbModuleInstallation() {
    viewModelScope.launch {
      installSmbModule().collect {
        _moduleInstallStatus.value = it
      }
    }
  }

  fun clearInstallStatus() {
    _moduleInstallStatus.value = null
  }

  private val _smbList = MutableStateFlow<List<Smb>>(emptyList())
  val smbList: StateFlow<List<Smb>> = _smbList.asStateFlow()

  private val _smbResState = MutableStateFlow<DataUiState<List<SmbFile>>>(DataUiState.Loading())
  val smbResState: StateFlow<DataUiState<List<SmbFile>>> = _smbResState.asStateFlow()

  init {
    viewModelScope.launch {
      smbRepository.allSmb().collect {
        _smbList.value = it
      }
    }
  }

  fun loadSmbRes(smb: Smb, url: String) {
    _smbResState.value = DataUiState.Loading()
    viewModelScope.launch {
      apiCall(smb, url)
    }
  }

  private suspend fun apiCall(smb: Smb, url: String) {
    val d = delegateProvider.getDelegate()
    if (d == null) {
      _smbResState.value = DataUiState.Error(Exception("SMB module not installed"))
      return
    }
    try {
      val files = d.listFiles(smb, url)
      _smbResState.value = DataUiState.Success(files)
    } catch (e: Exception) {
      Timber.e(e)
      _smbResState.value = DataUiState.Error(e)
    }
  }

  /** 删除远端文件，[relativePath] 为共享内相对路径 */
  suspend fun deleteRemoteFile(smb: Smb, relativePath: String): Boolean {
    val d = delegateProvider.getDelegate() ?: return false
    return try {
      d.delete(smb, relativePath)
      true
    } catch (e: Exception) {
      Timber.e(e, "delete smb file failed: $relativePath")
      MessageNotifier.show(e.localizedMessage ?: "Delete failed")
      false
    }
  }

  /** 离开页面时重置加载状态，避免下次进入读到残留 Error 而立即退出 */
  fun clearResState() {
    _smbResState.value = DataUiState.Loading()
  }

  fun deleteSmb(smb: Smb) = viewModelScope.launch {
    smbRepository.delete(smb)
    // 同步清理该源的缓存歌曲与内存解析表，并从播放队列移除
    val removedIds = remoteSongLookup.clearSource("smb:${smb.server}:${smb.share}")
    if (removedIds.isNotEmpty()) {
      MusicServiceRemote.removeFromQueue(removedIds)
    }
  }

  /**
   * 启用/禁用音源。禁用时清掉该源的缓存歌曲、内存解析表与播放队列。
   *
   * 为 suspend 方法：调用方需在本方法**返回后**再刷新曲库，避免清理与刷新并发导致的旧数据残留。
   */
  suspend fun setEnabled(smb: Smb, enabled: Boolean) {
    // 不要就地修改传入实例（会导致列表不重组、开关状态不刷新），写库用 copy 生成新实例
    smbRepository.insertOrReplace(smb.copy(enabled = enabled).also { it.id = smb.id })
    if (!enabled) {
      val removedIds = remoteSongLookup.clearSource("smb:${smb.server}:${smb.share}")
      if (removedIds.isNotEmpty()) {
        MusicServiceRemote.removeFromQueue(removedIds)
      }
    }
  }

  /** 一次性查询（不依赖列表 Flow 的刷新时机），供导航后立即按 id 恢复实体 */
  suspend fun getSmbById(id: Int): Smb? = smbRepository.byId(id)

  fun updateLastUrl(smb: Smb, newUrl: String) = viewModelScope.launch {
    if (smb.lastUrl == newUrl) {
      return@launch
    }
    smb.lastUrl = newUrl
    smbRepository.insertOrReplace(smb)
  }

  /**
   * 保存导入根目录，并把浏览位置重置到该目录。
   * [relativePath] 为共享内相对路径，根级（空串）时清除 [Smb.rootDir] 即导入整个共享。
   *
   * 为 suspend 方法：调用方需在本方法**返回后**再刷新曲库，确保后台枚举读到的是新 rootDir，
   * 否则会按旧目录做 diff，导致新目录下的歌曲无法立即导入。
   */
  suspend fun updateRootDir(smb: Smb, relativePath: String, currentUrl: String) {
    val updated = smb.copy(
      rootDir = relativePath.trim { it == '/' || it == '\\' }.takeIf { it.isNotEmpty() },
      lastUrl = if (currentUrl.isNotBlank()) currentUrl else smb.lastUrl
    ).also { it.id = smb.id }
    smbRepository.insertOrReplace(updated)
  }

  suspend fun saveSmb(smb: Smb): Boolean {
    // 兜底清理，防止输入框带入不可见的换行/空格
    smb.account = smb.account.trim()
    smb.pwd = smb.pwd.trim()
    smb.server = smb.server.trim()
    smb.share = smb.share.trim { it == '/' || it == '\\' || it == ' ' }
    smb.domain = smb.domain?.trim()?.ifEmpty { null }
    val d = delegateProvider.getDelegate()
    if (d == null) {
      MessageNotifier.show("SMB module not installed")
      return false
    }
    return try {
      d.checkConnection(smb)
      // Room 不会把自增主键写回实体，需手动回填，否则后续按 id 导航/查询会拿到 0
      val rowId = smbRepository.insertOrReplace(smb)
      if (smb.id == 0 && rowId > 0) {
        smb.id = rowId.toInt()
      }
      true
    } catch (e: Exception) {
      Timber.e(e)
      MessageNotifier.show(e.localizedMessage ?: "Save failed")
      false
    }
  }

  fun insertOrReplaceSmb(smb: Smb) = viewModelScope.launch {
    saveSmb(smb)
  }

  private val _addSmbState = MutableStateFlow(AddSmbState(DialogState()))
  val addSmbState = _addSmbState.asStateFlow()

  fun updateAddSmbState(
    alias: String? = null,
    domain: String? = null,
    account: String? = null,
    pwd: String? = null,
    server: String? = null,
    share: String? = null
  ) {
    _addSmbState.update {
      it.copy(
        alias = alias ?: it.alias,
        domain = domain ?: it.domain,
        account = account ?: it.account,
        pwd = pwd ?: it.pwd,
        server = server ?: it.server,
        share = share ?: it.share
      )
    }
  }

  fun showAddSmbDialog(editSmb: Smb? = null) {
    _addSmbState.update {
      it.dialogState.show()
      it.copy(
        alias = editSmb?.alias ?: it.alias,
        domain = editSmb?.domain ?: it.domain,
        account = editSmb?.account ?: it.account,
        pwd = editSmb?.pwd ?: it.pwd,
        server = editSmb?.server ?: it.server,
        share = editSmb?.share ?: it.share,
        editSmb = editSmb,
        availableShares = emptyList(),
        isLoadingShares = false,
        showShareSelection = false
      )
    }
  }

  fun dismissShareSelection() {
    _addSmbState.update { it.copy(showShareSelection = false) }
  }

  suspend fun fetchMeta(song: Song.Remote) = fetchMetaDataUseCase(song)
}

@Stable
data class AddSmbState(
  val dialogState: DialogState,
  val editSmb: Smb? = null,
  val alias: String = "",
  val domain: String = "",
  val account: String = "",
  val pwd: String = "",
  val server: String = "",
  val share: String = "",
  val availableShares: List<String> = emptyList(),
  val isLoadingShares: Boolean = false,
  val showShareSelection: Boolean = false
)
