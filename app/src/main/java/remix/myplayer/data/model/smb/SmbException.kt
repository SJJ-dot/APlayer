package remix.myplayer.data.model.smb

class SmbException(
  message: String? = null,
  cause: Throwable? = null,
  val isNotFound: Boolean = false,
  /** 登录 / 鉴权失败（账号密码错误、账号被禁用、无共享权限等） */
  val isAuthFailed: Boolean = false
) : Exception(message, cause)
