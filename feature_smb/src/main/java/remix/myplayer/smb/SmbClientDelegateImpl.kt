package remix.myplayer.smb

import androidx.annotation.Keep
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import remix.myplayer.data.db.room.entity.Smb
import remix.myplayer.data.model.smb.SmbClientDelegate
import remix.myplayer.data.model.smb.SmbException
import remix.myplayer.data.model.smb.SmbFile

/** 鉴权类 NT 状态：账号密码错误、无访问权限、账号被禁用、密码过期 */
private fun NtStatus.isAuthFailure(): Boolean =
  this == NtStatus.STATUS_LOGON_FAILURE ||
      this == NtStatus.STATUS_ACCESS_DENIED ||
      this == NtStatus.STATUS_ACCOUNT_DISABLED ||
      this == NtStatus.STATUS_PASSWORD_EXPIRED

@Keep
class SmbClientDelegateImpl : SmbClientDelegate {

  override suspend fun listFiles(smb: Smb, url: String): List<SmbFile> =
    withContext(Dispatchers.IO) {
      try {
        SMBClient().use { client ->
          val (host, port) = Smb.parseServerAddress(smb.server)
          val connection = if (port != null) client.connect(host, port) else client.connect(host)
          connection.use {
            val authContext = AuthenticationContext(smb.account, smb.pwd.toCharArray(), smb.domain)
            // 认证阶段的失败一律按「登录失败」处理（账号密码错误 / 账号被禁用 / 鉴权协议失败等）；
            // 传输层异常（连接被重置等）按网络错误处理，避免误判为登录失败而清空该音源数据
            val session = try {
              connection.authenticate(authContext)
            } catch (e: TransportException) {
              throw SmbException(e.message, e)
            } catch (e: Exception) {
              throw SmbException(e.message, e, isAuthFailed = true)
            }
            session.use {
              val diskShare = session.connectShare(smb.share) as DiskShare
              diskShare.use { share ->
                val relativePath = smb.getRelativePath(url).replace('/', '\\')
                val fileInfos = share.list(relativePath)
                fileInfos.map {
                  val fileName = it.fileName
                  val fileRelativePath =
                    if (relativePath.isEmpty()) fileName else "$relativePath\\$fileName"
                  SmbFile(
                    name = fileName,
                    isDirectory = (it.fileAttributes and 16L) != 0L,
                    path = fileRelativePath.replace('\\', '/'),
                    size = it.endOfFile,
                    lastModified = it.changeTime.toEpochMillis()
                  )
                }.filter { it.name != "." && it.name != ".." }.filter {
                  it.isDirectory || it.isAudio
                }
              }
            }
          }
        }
      } catch (e: SMBApiException) {
        throw SmbException(
          e.message, e,
          isNotFound = e.status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND ||
              e.status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
          isAuthFailed = e.status.isAuthFailure()
        )
      } catch (e: SmbException) {
        // 认证阶段已判定为登录失败的异常：保持原样向上抛出，不要在此丢失标记
        throw e
      } catch (e: Exception) {
        throw SmbException(e.message, e)
      }
    }

  override suspend fun delete(smb: Smb, relativePath: String) {
    withContext(Dispatchers.IO) {
      try {
        SMBClient().use { client ->
          val (host, port) = Smb.parseServerAddress(smb.server)
          val connection = if (port != null) client.connect(host, port) else client.connect(host)
          connection.use {
            val authContext = AuthenticationContext(smb.account, smb.pwd.toCharArray(), smb.domain)
            val session = connection.authenticate(authContext)
            session.use {
              val diskShare = session.connectShare(smb.share) as DiskShare
              diskShare.use { share ->
                val path = relativePath.replace('/', '\\').trimStart('\\')
                try {
                  share.rm(path)
                } catch (e: Exception) {
                  // 目录不能用 rm 删除，回退为递归删除
                  share.rmdir(path, true)
                }
              }
            }
          }
        }
      } catch (e: SMBApiException) {
        throw SmbException(
          e.message, e, e.status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND ||
              e.status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND
        )
      } catch (e: Exception) {
        throw SmbException(e.message, e)
      }
    }
  }

  override suspend fun checkConnection(smb: Smb) {
    withContext(Dispatchers.IO) {
      SMBClient().use { client ->
        val (host, port) = Smb.parseServerAddress(smb.server)
        val connection = if (port != null) client.connect(host, port) else client.connect(host)
        connection.use {
          val authContext = AuthenticationContext(smb.account, smb.pwd.toCharArray(), smb.domain)
          val session = connection.authenticate(authContext)
          session.use {
            val diskShare = session.connectShare(smb.share) as DiskShare
            diskShare.use {
              // Just to check if connection works
            }
          }
        }
      }
    }
  }
}
