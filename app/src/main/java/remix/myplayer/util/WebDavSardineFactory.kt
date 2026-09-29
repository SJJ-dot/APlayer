package remix.myplayer.util

import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.OkHttpClient

/**
 * 创建带「预认证」（preemptive auth）的 Sardine 客户端：
 * 每个请求直接携带 Basic Authorization 头，避免先发无凭据请求、收到 401 挑战后再重试——
 * 部分服务器（如坚果云）对这种挑战/重试流偶发 401 失败。
 */
object WebDavSardineFactory {

  fun create(account: String, pwd: String): OkHttpSardine {
    val accountTrim = account.trim()
    val pwdTrim = pwd.trim()
    return OkHttpSardine(createClient(accountTrim, pwdTrim)).apply {
      setCredentials(accountTrim, pwdTrim)
    }
  }

  /**
   * 带预认证（Basic Authorization）的 OkHttp 客户端。
   * 用于需要认证的资源请求，例如读取 WebDAV 文件的内嵌封面。
   */
  fun createClient(account: String, pwd: String): OkHttpClient {
    val credential = Credentials.basic(account.trim(), pwd.trim())
    return OkHttpClient.Builder()
      .connectTimeout(20L, TimeUnit.SECONDS)
      .readTimeout(20L, TimeUnit.SECONDS)
      .writeTimeout(20L, TimeUnit.SECONDS)
      .addInterceptor { chain ->
        chain.proceed(
          chain.request().newBuilder()
            .header("Authorization", credential)
            .build()
        )
      }
      .build()
  }
}
