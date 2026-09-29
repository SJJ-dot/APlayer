package remix.myplayer.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网络状态观察者。
 *
 * 提供 [epoch]（网络世代号）：每次网络可用时自增一次。
 * 用于「网络错误时不回退音源、等网络变化后再重试」的语义——
 * 失败标记记录所属世代，世代变化即视为可重试。
 */
@Singleton
class NetworkWatcher @Inject constructor(
  @param:ApplicationContext private val context: Context
) {

  /** 网络世代号，网络恢复/切换时 +1 */
  @Volatile
  var epoch: Long = 0L
    private set

  /** 当前是否有可用网络 */
  @Volatile
  var isOnline: Boolean = true
    private set

  init {
    runCatching {
      val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
      @Suppress("DEPRECATION")
      isOnline = manager.activeNetworkInfo?.isConnected == true
      manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          isOnline = true
          epoch++
        }

        override fun onLost(network: Network) {
          isOnline = false
        }

        override fun onUnavailable() {
          isOnline = false
        }
      })
    }.onFailure {
      Timber.v(it, "register network callback failed")
    }
  }
}
