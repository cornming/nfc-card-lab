package io.github.cornming.nfccardlab

import io.github.cornming.nfccardlab.AccessPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessPolicyTest {

    private val a = "AA:AA:AA:AA"
    private val b = "BB:BB:BB:BB"

    @Test
    fun unregisteredCardIsAlwaysDenied() {
        val p = AccessPolicy(cooldownMs = 5_000)
        assertEquals(Decision.DENY_UNREGISTERED, p.decide(a, registered = false, nowMs = 0))
        assertEquals(Decision.DENY_UNREGISTERED, p.decide(a, registered = false, nowMs = 100_000))
    }

    @Test
    fun firstRegisteredScanIsAllowed() {
        val p = AccessPolicy(cooldownMs = 5_000)
        assertEquals(Decision.ALLOW, p.decide(a, true, 0))
    }

    @Test
    fun secondScanOfSameCardWithinCooldownIsDenied() {
        val p = AccessPolicy(cooldownMs = 5_000)
        assertEquals(Decision.ALLOW, p.decide(a, true, 1_000))
        assertEquals(Decision.DENY_COOLDOWN, p.decide(a, true, 4_999))
    }

    @Test
    fun scanAfterCooldownIsAllowedAgain() {
        val p = AccessPolicy(cooldownMs = 5_000)
        assertEquals(Decision.ALLOW, p.decide(a, true, 1_000))
        assertEquals(Decision.ALLOW, p.decide(a, true, 6_000))
    }

    @Test
    fun cooldownIsPerCard() {
        val p = AccessPolicy(cooldownMs = 5_000)
        assertEquals(Decision.ALLOW, p.decide(a, true, 1_000))
        // 另一張卡不受 A 的冷卻影響
        assertEquals(Decision.ALLOW, p.decide(b, true, 1_500))
        assertEquals(Decision.DENY_COOLDOWN, p.decide(a, true, 2_000))
        assertEquals(Decision.DENY_COOLDOWN, p.decide(b, true, 2_000))
    }

    @Test
    fun deniedScansDoNotExtendCooldown() {
        val p = AccessPolicy(cooldownMs = 5_000)
        assertEquals(Decision.ALLOW, p.decide(a, true, 0))
        assertEquals(Decision.DENY_COOLDOWN, p.decide(a, true, 3_000))
        assertEquals(Decision.DENY_UNREGISTERED, p.decide(b, false, 4_000))
        // 距離上次「成功執行」已滿 5 秒，即使中間有被拒絕的刷卡也應該允許
        assertEquals(Decision.ALLOW, p.decide(a, true, 5_000))
    }

    @Test
    fun unregisteredScanDoesNotStartCooldown() {
        val p = AccessPolicy(cooldownMs = 5_000)
        assertEquals(Decision.DENY_UNREGISTERED, p.decide(a, false, 0))
        assertEquals(Decision.ALLOW, p.decide(a, true, 1))
    }

    @Test
    fun dryRunActuatorReportsSuccessWithoutSideEffects() {
        var result: Result<String>? = null
        DryRunActuator().execute(ActionContext(a, "", "t")) { result = it }
        assertEquals(true, result?.isSuccess)
    }
}
