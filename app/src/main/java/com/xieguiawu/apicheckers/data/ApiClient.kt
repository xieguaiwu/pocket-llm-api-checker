package com.xieguiawu.apicheckers.data

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** OkHttp 单例：所有网络超时 15s（全局约束 5） */
object ApiClient {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 移动端 UA，Zen billing 页面按浏览器解析 */
    val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /** 桌面浏览器 UA：阿里云控制台网关对移动端 UA 会降级处理（sec_token 不渲染） */
    val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
}
