package io.github.cornming.nfccardlab

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/**
 * 「動作」的抽象介面。每種動作各一個實作：模擬、HTTP POST，
 * 之後可以再加 Google Home 自動化等。
 */
interface Actuator {
    /** 顯示給使用者看的名稱，不含密鑰。 */
    val name: String

    /**
     * 執行一次動作。完成後呼叫 [onResult]（可能在背景執行緒）。
     * 成功的 Result 內容與失敗的訊息會直接顯示並寫進紀錄，所以**不能包含密鑰、網址或標頭內容**。
     */
    fun execute(ctx: ActionContext, onResult: (Result<String>) -> Unit)
}

/** 模擬用：不會控制任何真實裝置，只用來驗證「刷卡 → 規則 → 動作」這條路是否正確。 */
class DryRunActuator : Actuator {
    override val name: String = ActionConfig.DryRun.summary()

    override fun execute(ctx: ActionContext, onResult: (Result<String>) -> Unit) {
        onResult(Result.success("已模擬執行，沒有任何實際動作"))
    }
}

/** 對指定網址送出 HTTP POST。網路請求在背景執行緒進行。 */
class HttpPostActuator(private val config: ActionConfig.HttpPost) : Actuator {

    override val name: String = config.summary()

    override fun execute(ctx: ActionContext, onResult: (Result<String>) -> Unit) {
        EXECUTOR.execute {
            onResult(runCatching { send(ctx) })
        }
    }

    private fun send(ctx: ActionContext): String {
        config.validate()?.let { throw IllegalStateException(it) }
        val headers = HeaderText.parse(config.headersText)
        val json = config.contentType.contains("json", ignoreCase = true)
        val body = Template.render(config.body, ctx, json)
        val response = try {
            HttpPoster.post(config.url.trim(), body, config.contentType.trim(), headers)
        } catch (_: SocketTimeoutException) {
            throw IllegalStateException("連線逾時")
        } catch (e: IOException) {
            throw IllegalStateException("連線失敗（${e.javaClass.simpleName}）")
        }
        if (response.code !in 200..299) {
            throw IllegalStateException("伺服器回應 HTTP ${response.code}")
        }
        return "POST 成功（HTTP ${response.code}）"
    }

    private companion object {
        val EXECUTOR = Executors.newSingleThreadExecutor { r ->
            Thread(r, "http-post").apply { isDaemon = true }
        }
    }
}

object ActuatorFactory {
    fun create(config: ActionConfig): Actuator = when (config) {
        is ActionConfig.DryRun -> DryRunActuator()
        is ActionConfig.HttpPost -> HttpPostActuator(config)
    }
}
