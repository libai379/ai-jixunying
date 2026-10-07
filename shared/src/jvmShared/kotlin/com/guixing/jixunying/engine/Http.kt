package com.guixing.jixunying.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.http
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.util.concurrent.ConcurrentHashMap

/** 直连一个客户端，走代理一个客户端（国外接口一般要代理，国内的不要）。 */
object Http {
    private val clients = ConcurrentHashMap<String, HttpClient>()

    fun client(proxyAddr: String?): HttpClient {
        val key = proxyAddr?.trim().orEmpty()
        return clients.getOrPut(key) {
            HttpClient(OkHttp) {
                expectSuccess = false
                install(HttpTimeout) {
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = 180_000
                    requestTimeoutMillis = 600_000
                }
                engine {
                    if (key.isNotEmpty()) {
                        val url = if (key.startsWith("http")) key else "http://$key"
                        proxy = ProxyBuilder.http(url)
                    }
                    config { retryOnConnectionFailure(true) }
                }
            }
        }
    }

    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"
}
