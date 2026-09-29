package remix.myplayer.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import remix.myplayer.R
import remix.myplayer.data.db.room.entity.Smb
import remix.myplayer.ui.widget.common.EditField
import remix.myplayer.viewmodel.SmbViewModel

/**
 * [onPositive] 的最后一个参数 [onResult]：保存完成后回调，true 成功（弹窗关闭），
 * false 失败（弹窗保持打开，输入不丢失，便于修改后重试）。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AddSmbDialog(
  vm: SmbViewModel = viewModel(),
  onPositive: (String, String, String, String, String, String, Smb?, (Boolean) -> Unit) -> Unit
) {
  val state by vm.addSmbState.collectAsStateWithLifecycle()

  val alias = state.alias
  val domain = state.domain
  val account = state.account
  val pwd = state.pwd
  val server = state.server
  val share = state.share

  fun reset() {
    vm.updateAddSmbState("", "", "", "", "", "")
  }

  NormalDialog(
    dialogState = state.dialogState,
    autoDismiss = false,
    onDismissRequest = {
      reset()
    },
    titleRes = R.string.smb,
    positiveRes = if (state.editSmb == null) R.string.add else R.string.update,
    onPositive = {
      onPositive(alias, domain, account, pwd, server, share, state.editSmb) { success ->
        if (success) {
          reset()
          state.dialogState.dismiss()
        }
      }
    },
    negativeRes = null,
    custom = {
      Column {
        EditField(alias, R.string.alias, isError = alias.isEmpty()) {
          vm.updateAddSmbState(alias = it)
        }
        EditField(server, R.string.webdav_hint_server, isError = server.isEmpty()) {
          vm.updateAddSmbState(server = it)
        }
        EditField(share, R.string.share, isError = share.isEmpty()) {
          vm.updateAddSmbState(share = it)
        }
        EditField(domain, R.string.domain, isError = false) {
          vm.updateAddSmbState(domain = it)
        }
        EditField(
          account,
          R.string.account,
          isError = account.isEmpty(),
          contentType = ContentType.Username
        ) {
          vm.updateAddSmbState(account = it)
        }
        EditField(
          pwd,
          R.string.pwd,
          isError = pwd.isEmpty(),
          contentType = ContentType.Password,
          keyboardType = KeyboardType.Password,
          isPassword = true
        ) {
          vm.updateAddSmbState(pwd = it)
        }
      }
    }
  )
}
