package remix.myplayer.ui.screen.source

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import remix.myplayer.R
import remix.myplayer.data.db.room.entity.Smb
import remix.myplayer.data.db.room.entity.WebDav
import remix.myplayer.misc.MediaScanner
import remix.myplayer.ui.dialog.AddSmbDialog
import remix.myplayer.ui.dialog.AddWebDavDialog
import remix.myplayer.ui.dialog.FolderDialog
import remix.myplayer.ui.dialog.NormalDialog
import remix.myplayer.ui.dialog.rememberDialogState
import remix.myplayer.ui.dialog.runWithLoading
import remix.myplayer.ui.nav.LocalNavController
import remix.myplayer.ui.nav.SmbPickRoute
import remix.myplayer.ui.nav.WebDavPickRoute
import remix.myplayer.ui.screen.setting.SwitchPreference
import remix.myplayer.ui.screen.setting.logic.common.ScanSizeLogic
import remix.myplayer.ui.theme.LocalTheme
import remix.myplayer.ui.widget.common.PopupButton
import remix.myplayer.ui.widget.common.TextPrimary
import remix.myplayer.ui.widget.common.TextSecondary
import remix.myplayer.util.ext.clickWithRipple
import remix.myplayer.viewmodel.libraryViewModel
import remix.myplayer.viewmodel.settingViewModel
import remix.myplayer.viewmodel.smbViewModel
import remix.myplayer.viewmodel.webDavViewModel
import java.io.File

/**
 * 音源管理：本地音乐（启用 / 自动扫描 / 手动扫描）+ 远程音源（WebDAV / SMB，可启用、禁用）。
 */
@Composable
fun SourceManageScreen() {
  val nav = LocalNavController.current
  val theme = LocalTheme.current
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  val webDavVM = webDavViewModel
  val smbVM = smbViewModel
  val libraryVM = libraryViewModel
  val settingVM = settingViewModel
  val settingState by settingVM.settingsState.collectAsStateWithLifecycle()

  val webDavList by webDavVM.webDavList.collectAsStateWithLifecycle()
  val smbList by smbVM.smbList.collectAsStateWithLifecycle()

  // 本地音乐被禁用时，本地扫描相关项一律不可操作
  val localMusicEnabled = settingState.common.localMusicEnabled

  val deleteDialogState = rememberDialogState()
  var pendingDeleteWebDav by remember { mutableStateOf<WebDav?>(null) }
  var pendingDeleteSmb by remember { mutableStateOf<Smb?>(null) }

  // 手动扫描：选择目录后交给系统媒体扫描器，扫描完成显式刷新曲库
  val manualScanDialogState = rememberDialogState()
  var manualScanPath by rememberSaveable {
    mutableStateOf(settingState.common.manualScanFolder)
  }

  androidx.compose.material3.Scaffold(
    topBar = {
      remix.myplayer.ui.widget.common.CommonAppBar(
        title = stringResource(R.string.source_manage),
        actions = emptyList()
      )
    },
    containerColor = theme.mainBackground,
  ) { contentPadding ->
    LazyColumn(modifier = Modifier.fillMaxSize().padding(contentPadding)) {

      item { SectionHeader(stringResource(R.string.local_music)) }
      item {
        SwitchPreference(
          title = stringResource(R.string.enable_local_music),
          content = stringResource(R.string.enable_local_music_tip),
          checked = settingState.common.localMusicEnabled
        ) { enabled ->
          settingVM.setLocalMusicEnabled(enabled)
          libraryVM.fetchMedia()
        }
      }
      item {
        SwitchPreference(
          title = stringResource(R.string.auto_scan),
          content = stringResource(R.string.auto_scan_tip),
          checked = settingState.common.autoScanLocal,
          enabled = localMusicEnabled
        ) { enabled ->
          settingVM.setAutoScanLocal(enabled)
        }
      }
      item {
        PreferenceRow(
          title = stringResource(R.string.manual_scan),
          summary = stringResource(R.string.manual_scan_tip),
          enabled = localMusicEnabled
        ) {
          if (localMusicEnabled) {
            manualScanDialogState.show()
          }
        }
      }
      item { ScanSizeLogic(enabled = localMusicEnabled) }

      item { SectionHeader(stringResource(R.string.webdav)) }
      items(webDavList, key = { "webdav_${it.id}" }) { webDav ->
        RemoteSourceRow(
          title = webDav.alias,
          summary = listOfNotNull(
            webDav.account,
            webDav.rootDir?.removePrefix(webDav.base())?.trim('/')
              ?.takeIf { it.isNotEmpty() }
          ).joinToString(" · "),
          enabled = webDav.enabled,
          onEnabledChange = { enabled ->
            scope.launch {
              // 必须先等禁用清理（写库 + 清缓存）完成，再刷新曲库：
              // 并发执行时后台枚举可能读到旧的 enabled 状态并把歌曲重新写回缓存
              webDavVM.setEnabled(webDav, enabled)
              libraryVM.fetchMedia()
            }
          },
          onRowClick = { nav.navigate(WebDavPickRoute(webDav.id)) }
        ) {
          when (it) {
            R.string.edit -> webDavVM.showAddWebDavDialog(webDav)
            R.string.delete -> {
              pendingDeleteWebDav = webDav
              pendingDeleteSmb = null
              deleteDialogState.show()
            }
          }
        }
      }
      item {
        PreferenceRow(title = stringResource(R.string.add_webdav), summary = "") {
          webDavVM.showAddWebDavDialog(null)
        }
      }

      item { SectionHeader(stringResource(R.string.smb)) }
      items(smbList, key = { "smb_${it.id}" }) { smb ->
        RemoteSourceRow(
          title = smb.alias,
          summary = listOfNotNull(smb.account, smb.rootDir).joinToString(" · "),
          enabled = smb.enabled,
          onEnabledChange = { enabled ->
            scope.launch {
              // 先完成禁用清理，再刷新曲库（避免读到旧状态把歌曲写回）
              smbVM.setEnabled(smb, enabled)
              libraryVM.fetchMedia()
            }
          },
          onRowClick = { nav.navigate(SmbPickRoute(smb.id)) }
        ) {
          when (it) {
            R.string.edit -> smbVM.showAddSmbDialog(smb)
            R.string.delete -> {
              pendingDeleteWebDav = null
              pendingDeleteSmb = smb
              deleteDialogState.show()
            }
          }
        }
      }
      item {
        PreferenceRow(title = stringResource(R.string.add_smb), summary = "") {
          smbVM.showAddSmbDialog(null)
        }
      }
    }
  }

  FolderDialog(
    dialogState = manualScanDialogState,
    initialFolder = manualScanPath,
    onFolderSelection = {
      manualScanPath = it.absolutePath
    },
    onPositive = { path ->
      manualScanDialogState.dismiss()
      settingVM.setManualScanFolder(path)
      scope.launch {
        MediaScanner(context).scan(File(path))
        // 关闭「自动扫描」时媒体库变化不会自动刷新，这里显式刷新一次
        libraryVM.fetchMedia()
      }
    }
  )

  AddWebDavDialog { alias, account, pwd, server, editWebDav, onResult ->
    // 清理输入中的空格/换行，避免污染认证头导致 401
    val aliasTrim = alias.trim()
    val accountTrim = account.trim()
    val pwdTrim = pwd.trim()
    val serverTrim = server.trim().removeSuffix("/")
    if (aliasTrim.isEmpty() || accountTrim.isEmpty() || pwdTrim.isEmpty() || serverTrim.isEmpty()) {
      onResult(false)
      return@AddWebDavDialog
    }
    if (editWebDav != null) {
      val updated = editWebDav.copy(
        alias = aliasTrim, account = accountTrim, pwd = pwdTrim,
        server = serverTrim, lastUrl = serverTrim
      ).also { it.id = editWebDav.id }
      scope.runWithLoading {
        onResult(webDavVM.saveWebDav(updated))
      }
    } else {
      val newWebDav = WebDav(aliasTrim, accountTrim, pwdTrim, serverTrim, serverTrim)
      // 保存成功（拿到 id）后再进入选目录页
      scope.runWithLoading {
        val success = webDavVM.saveWebDav(newWebDav)
        if (success) {
          nav.navigate(WebDavPickRoute(newWebDav.id))
        }
        onResult(success)
      }
    }
  }

  AddSmbDialog(smbVM) { alias, domain, account, pwd, server, share, editSmb, onResult ->
    // 清理输入中的空格/换行
    val aliasTrim = alias.trim()
    val domainTrim = domain.trim()
    val accountTrim = account.trim()
    val pwdTrim = pwd.trim()
    val serverTrim = server.trim()
    val shareTrim = share.trim()
    if (aliasTrim.isEmpty() || accountTrim.isEmpty() || pwdTrim.isEmpty()
      || serverTrim.isEmpty() || shareTrim.isEmpty()
    ) {
      onResult(false)
      return@AddSmbDialog
    }
    val lastUrl = serverTrim.removeSuffix("/") + "/" + shareTrim
    if (editSmb != null) {
      val updated = editSmb.copy(
        alias = aliasTrim, domain = domainTrim.ifEmpty { null }, account = accountTrim,
        pwd = pwdTrim, server = serverTrim, share = shareTrim, lastUrl = lastUrl
      ).also { it.id = editSmb.id }
      scope.runWithLoading {
        onResult(smbVM.saveSmb(updated))
      }
    } else {
      val newSmb = Smb(
        aliasTrim, domainTrim.ifEmpty { null }, accountTrim, pwdTrim, serverTrim, shareTrim, lastUrl
      )
      // 保存成功（拿到 id）后再进入选目录页
      scope.runWithLoading {
        val success = smbVM.saveSmb(newSmb)
        if (success) {
          nav.navigate(SmbPickRoute(newSmb.id))
        }
        onResult(success)
      }
    }
  }

  NormalDialog(
    dialogState = deleteDialogState,
    title = stringResource(R.string.delete),
    content = pendingDeleteWebDav?.alias ?: pendingDeleteSmb?.alias ?: "",
    onPositive = {
      pendingDeleteWebDav?.let { webDavVM.deleteWebDav(it) }
      pendingDeleteSmb?.let { smbVM.deleteSmb(it) }
      pendingDeleteWebDav = null
      pendingDeleteSmb = null
      // 立即刷新歌曲列表
      libraryVM.fetchMedia()
    }
  )
}

@Composable
private fun SectionHeader(text: String) {
  TextPrimary(
    text,
    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 8.dp)
  )
}

@Composable
private fun PreferenceRow(
  title: String,
  summary: String,
  enabled: Boolean = true,
  onClick: () -> Unit
) {
  val theme = LocalTheme.current
  androidx.compose.foundation.layout.Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(56.dp)
      .then(if (enabled) Modifier.clickWithRipple(false) { onClick() } else Modifier)
      .padding(horizontal = 16.dp)
      .alpha(if (enabled) 1f else 0.4f),
    verticalAlignment = Alignment.CenterVertically
  ) {
    androidx.compose.foundation.layout.Column(
      modifier = Modifier.weight(1f)
    ) {
      TextPrimary(title)
      if (summary.isNotEmpty()) {
        androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
        TextSecondary(summary)
      }
    }
  }
}

@Composable
private fun RemoteSourceRow(
  title: String,
  summary: String,
  enabled: Boolean,
  onEnabledChange: (Boolean) -> Unit,
  onRowClick: () -> Unit,
  onMenuClick: (Int) -> Unit
) {
  val theme = LocalTheme.current
  androidx.compose.foundation.layout.Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(56.dp)
      .clickWithRipple(false) { onRowClick() }
      .background(theme.mainBackground)
      .padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    androidx.compose.foundation.layout.Column(
      modifier = Modifier.weight(1f)
    ) {
      TextPrimary(title)
      androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
      TextSecondary(summary)
    }
    Switch(
      modifier = Modifier.scale(0.8f),
      checked = enabled,
      colors = SwitchDefaults.colors().copy(
        checkedTrackColor = theme.secondary,
        uncheckedTrackColor = Color.Transparent
      ),
      onCheckedChange = { onEnabledChange(it) }
    )
    PopupButton(
      listOf(R.string.edit, R.string.delete),
      contentDescription = "RemoteSourcePopup"
    ) { onMenuClick(it) }
  }
}
