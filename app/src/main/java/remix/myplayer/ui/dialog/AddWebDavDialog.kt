package remix.myplayer.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import remix.myplayer.R
import remix.myplayer.data.db.room.entity.WebDav
import remix.myplayer.ui.widget.common.EditField
import remix.myplayer.viewmodel.webDavViewModel

/**
 * [onPositive] 的最后一个参数 [onResult]：保存完成后回调，true 成功（弹窗关闭），
 * false 失败（弹窗保持打开，输入不丢失，便于修改后重试）。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AddWebDavDialog(onPositive: (String, String, String, String, WebDav?, (Boolean) -> Unit) -> Unit) {
  val vm = webDavViewModel
  val state by vm.addWebDavState.collectAsStateWithLifecycle()

  val alias = state.alias
  val account = state.account
  val pwd = state.pwd
  val server = state.server

  fun reset() {
    vm.updateAddWebDavState("", "", "", "")
  }

  NormalDialog(
    dialogState = state.dialogState,
    autoDismiss = false,
    onDismissRequest = {
      reset()
    },
    titleRes = R.string.webdav,
    positiveRes = if (state.editWebDav == null) R.string.add else R.string.update,
    onPositive = {
      onPositive(alias, account, pwd, server, state.editWebDav) { success ->
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
          vm.updateAddWebDavState(alias = it)
        }
        EditField(
          account,
          R.string.account,
          isError = account.isEmpty(),
          contentType = ContentType.Username
        ) {
          vm.updateAddWebDavState(account = it)
        }
        EditField(
          pwd,
          R.string.pwd,
          isError = pwd.isEmpty(),
          contentType = ContentType.Password,
          keyboardType = KeyboardType.Password,
          isPassword = true
        ) {
          vm.updateAddWebDavState(pwd = it)
        }
        EditField(
          server,
          R.string.webdav_hint_server,
          isError = server.isEmpty(),
          keyboardType = KeyboardType.Uri
        ) {
          vm.updateAddWebDavState(server = it)
        }
      }
    }
  )
}