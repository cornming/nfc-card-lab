package io.github.cornming.nfccardlab

/**
 * 「開鎖」動作的抽象介面。
 * 之後可以各寫一個實作：Google Home APIs、eWeLink 開放 API。
 */
interface Actuator {
    /** 顯示給使用者看的名稱。 */
    val name: String

    /** 執行一次開鎖。完成後呼叫 [onResult]（可能在任意執行緒）。 */
    fun unlock(onResult: (Result<String>) -> Unit)
}

/**
 * 模擬用：不會控制任何真實裝置，只用來驗證「刷卡 → 規則 → 動作」這條路是否正確。
 */
class DryRunActuator : Actuator {
    override val name: String = "模擬開鎖（尚未連接真實裝置）"

    override fun unlock(onResult: (Result<String>) -> Unit) {
        onResult(Result.success("已模擬開鎖，沒有任何實際動作"))
    }
}
