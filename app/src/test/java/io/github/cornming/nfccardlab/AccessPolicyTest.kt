package io.github.cornming.nfccardlab

import io.github.cornming.nfccardlab.AccessPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessPolicyTest {

    @Test
    fun unregisteredCardIsAlwaysDenied() {
        val p = AccessPolicy(cooldownMs = 5_000)
        assertEquals(Decision.DENY_UNREGISTERED, p.decide(registered = false, nowMs = 0))
        assertEquals(Decision.DENY_UNREGISTERED, p.decide(registered = false, nowMs = 100_000))
    }

    @Test
    fun firstRegisteredScanIsAllowed() {
        val p = AccessPolicy(cooldownMs = 5_000)
        assertEquals(Decision.ALLOW, p.decide(registered = true, nowMs = 0))
    }

    @Test
    fun secondScanWithinCooldownIsDenied() {
        val p = AccessPolicy(cooldownMs = 5_000)
        assertEquals(Decision.ALLOW, p.decide(true, 1_000))
        assertEquals(Decision.DENY_COOLDOWN, p.decide(true, 4_999))
    }

    @Test
    fun scanAfterCooldownIsAllowedAgain() {
        val p = AccessPolicy(cooldownMs = 5_000)
        assertEquals(Decision.ALLOW, p.decide(true, 1_000))
        assertEquals(Decision.ALLOW, p.decide(true, 6_000))
    }

    @Test
    fun deniedScansDoNotExtendCooldown() {
        val p = AccessPolicy(cooldownMs = 5_000)
        assertEquals(Decision.ALLOW, p.decide(true, 0))
        assertEquals(Decision.DENY_COOLDOWN, p.decide(true, 3_000))
        assertEquals(Decision.DENY_UNREGISTERED, p.decide(false, 4_000))
        // 距離上次「成功執行」已滿 5 秒，即使中間有被拒絕的刷卡也應該允許
        assertEquals(Decision.ALLOW, p.decide(true, 5_000))
    }

    @Test
    fun unregisteredScanDoesNotStartCooldown() {
        val p = AccessPolicy(cooldownMs = 5_000)
        assertEquals(Decision.DENY_UNREGISTERED, p.decide(false, 0))
        assertEquals(Decision.ALLOW, p.decide(true, 1))
    }

    @Test
    fun dryRunActuatorReportsSuccessWithoutSideEffects() {
        var result: Result<String>? = null
        DryRunActuator().unlock { result = it }
        assertEquals(true, result?.isSuccess)
    }
}
