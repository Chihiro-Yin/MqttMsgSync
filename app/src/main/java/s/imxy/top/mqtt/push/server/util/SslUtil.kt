package s.imxy.top.mqtt.push.server.util

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object SslUtil {
    fun getTrustAllSSLSocketFactory(): SSLSocketFactory {
        return try {
            // 创建一个信任所有证书的TrustManager
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                // 不校验客户端证书
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

                // 不校验服务器证书（关键：信任所有服务器证书）
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

                // 返回空的可接受 issuers
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })

            // 初始化SSL上下文，使用信任所有证书的TrustManager
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())

            // 返回创建的SSLSocketFactory
            sslContext.socketFactory
        } catch (e: Exception) {
            // 抛出运行时异常（实际项目中可根据需要处理）
            throw RuntimeException("创建SSL SocketFactory失败：${e.message}", e)
        }
    }
}