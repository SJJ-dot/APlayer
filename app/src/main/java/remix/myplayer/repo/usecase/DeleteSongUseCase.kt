package remix.myplayer.repo.usecase

import android.app.RecoverableSecurityException
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.Audio
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import remix.myplayer.R
import remix.myplayer.data.db.room.entity.PlayList
import remix.myplayer.data.model.audio.APlayerModel
import remix.myplayer.data.model.audio.Song
import remix.myplayer.data.prefs.SettingPrefs
import remix.myplayer.repo.AbstractRepository.Companion.makeInStrQuery
import remix.myplayer.repo.HistoryRepository
import remix.myplayer.repo.PlayListRepository
import remix.myplayer.repo.SongRepository
import remix.myplayer.repo.source.RemoteFileDeleter
import remix.myplayer.repo.source.RemoteSongLookup
import remix.myplayer.service.MusicServiceRemote
import remix.myplayer.ui.activity.base.BaseActivity
import remix.myplayer.ui.nav.MessageNotifier
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一的歌曲删除：本地与远程（WebDAV / SMB）一视同仁。
 *
 * - 「从曲库移除」：本地按 id 写入移除名单（MediaStore 查询排除），远程按 url 写入移除名单（远程刷新时排除）；
 * - 「删除源文件」：本地走 MediaStore 删除（Android 11+ 系统确认弹窗），远程按来源调用 WebDAV / SMB 删除远端文件；
 * - 无论哪种方式，都会同步清理播放队列、歌单（含收藏）与播放历史。
 */
@Singleton
class DeleteSongUseCase @Inject constructor(
  private val settingPrefs: SettingPrefs,
  private val songRepo: SongRepository,
  private val playListRepo: PlayListRepository,
  private val historyRepo: HistoryRepository,
  private val remoteSongLookup: RemoteSongLookup,
  private val remoteFileDeleter: RemoteFileDeleter
) {

  suspend operator fun invoke(
    activity: BaseActivity?,
    models: List<APlayerModel>,
    deleteSource: Boolean,
    parent: APlayerModel?
  ) =
    withContext(Dispatchers.Main) {
      if (activity == null || models.isEmpty()) {
        return@withContext
      }

      settingPrefs.deleteSource = deleteSource

      if (parent is PlayList) { // 从歌单中移除歌曲（本地 / 远程都用真实 id）
        val audioIds = models.filterIsInstance<Song>().map { it.id }
        if (audioIds.isNotEmpty()) {
          parent.audioIds.removeAll(audioIds.toSet())
          playListRepo.updatePlayList(parent)
        }

        if (!deleteSource) {
          activity.contentResolver.notifyChange(Audio.Media.EXTERNAL_CONTENT_URI, null)
          return@withContext
        }
      } else if (models.all { it is PlayList }) { // 删除歌单本身
        for (model in models) {
          val playList = model as PlayList
          if (playList.isFavorite()) {
            MessageNotifier.show(R.string.mylove_cant_edit)
            continue
          }

          playListRepo.deletePlayList(model.id)
        }

        if (!deleteSource) {
          return@withContext
        }
      }

      val songs = withContext(Dispatchers.IO) {
        songRepo.getSongsByModels(models)
      }
      if (songs.isEmpty()) {
        MessageNotifier.show(R.string.delete_success)
        activity.contentResolver.notifyChange(Audio.Media.EXTERNAL_CONTENT_URI, null)
        return@withContext
      }

      val localSongs = songs.filter { it.isLocal() }
      val remoteSongs = songs.filterIsInstance<Song.Remote>().filter { it.isRemote() }
      val allIds = songs.map { it.id }

      // 1. 从曲库移除：本地按 id，远程按 url（远程没有 MediaStore 记录）
      if (localSongs.isNotEmpty()) {
        settingPrefs.deleteIds = settingPrefs.deleteIds + localSongs.map { it.id.toString() }
      }

      // 2. 队列 / 歌单（含收藏）/ 历史 统一清理
      MusicServiceRemote.removeFromQueue(allIds)
      playListRepo.removeAudioIdsFromAll(allIds)
      withContext(Dispatchers.IO) {
        runCatching { historyRepo.deleteByAudioIds(allIds) }
          .onFailure { Timber.w(it, "delete history failed") }
      }

      // 3. 源文件处理
      if (deleteSource) {
        if (remoteSongs.isNotEmpty()) {
          val deleted = remoteFileDeleter.delete(remoteSongs)
          val failed = remoteSongs.filter { it.data !in deleted }
          if (failed.isNotEmpty()) {
            // 远端删除失败：保留“从曲库移除”，避免刷新后又出现
            settingPrefs.deleteRemoteUrls = settingPrefs.deleteRemoteUrls + failed.map { it.data }
            MessageNotifier.show(R.string.delete_error)
          } else if (localSongs.isEmpty()) {
            MessageNotifier.show(R.string.delete_success)
          }
          withContext(Dispatchers.IO) {
            remoteSongLookup.deleteSongs(remoteSongs.map { it.data })
          }
        }
        if (localSongs.isNotEmpty()) {
          deleteLocalSource(activity, localSongs)
        }
      } else if (remoteSongs.isNotEmpty()) {
        // 仅从曲库移除：加入远程移除名单并清理本地缓存，远端文件保留
        settingPrefs.deleteRemoteUrls = settingPrefs.deleteRemoteUrls + remoteSongs.map { it.data }
        withContext(Dispatchers.IO) {
          remoteSongLookup.deleteSongs(remoteSongs.map { it.data })
        }
      }

      // refresh ui
      activity.contentResolver.notifyChange(Audio.Media.EXTERNAL_CONTENT_URI, null)
    }

  /** 本地文件删除（Android 11+ 走系统删除确认，低版本直接删除） */
  private suspend fun deleteLocalSource(activity: BaseActivity, songs: List<Song>) =
    withContext(Dispatchers.IO) {
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          val uris = songs.map { it.contentUri }
          val sender: IntentSender =
            MediaStore.createDeleteRequest(activity.contentResolver, uris).intentSender
          activity.deleteSongLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } else {
          try {
            val count = activity.contentResolver.delete(
              Audio.Media.EXTERNAL_CONTENT_URI,
              makeInStrQuery(songs.map { it.id }),
              null
            )
            Timber.v("remove from mediaStore: $count")

            songs.forEach { song ->
              val file = File(song.data)
              if (file.exists() && file.canWrite()) {
                file.delete()
              }
            }

            MessageNotifier.show(R.string.delete_success)
          } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
              activity.deleteSongLauncher.launch(
                IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
              )
              return@withContext
            }
            throw e
          }
        }
        Timber.v("delete may success")
      } catch (e: Exception) {
        MessageNotifier.show(R.string.delete_error)
        Timber.v("delete failed: $e")
      }
    }
}
