package com.nexus.phone.nexus.policy

import com.nexus.phone.nexus.config.NexusConfig
import com.nexus.phone.nexus.config.SimConfig
import com.nexus.phone.nexus.config.SimPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CallPolicyControllerTest {
    @Before
    fun resetState() {
        CallSessionState.reset()
    }

    @Test
    fun ringing_ai_requestsAnswerAndSkipsIncomingUi() {
        val cfg =
            NexusConfig.default().copy(
                dialerTakeover = true,
                sims = listOf(SimConfig(0, "卡1", policy = SimPolicy.AI)),
            )
        val c =
            CallPolicyController(
                loadConfig = { cfg },
                takeoverEnabled = { true },
                slotResolver = { _, _ -> 0 },
            )
        val d = c.onRinging("acct", 0, "10086")
        assertTrue(d.actions.contains(PolicyAction.AnswerAi))
        assertFalse(d.actions.contains(PolicyAction.ShowIncomingUi))
        assertTrue(d.aiMode)
    }

    @Test
    fun ringing_human_showsIncomingUi() {
        val cfg =
            NexusConfig.default().copy(
                dialerTakeover = true,
                sims = listOf(SimConfig(0, "卡1", policy = SimPolicy.HUMAN)),
            )
        val c =
            CallPolicyController(
                loadConfig = { cfg },
                takeoverEnabled = { true },
                slotResolver = { _, _ -> 0 },
            )
        val d = c.onRinging(null, 0, "10086")
        assertTrue(d.actions.contains(PolicyAction.ShowIncomingUi))
        assertFalse(d.actions.contains(PolicyAction.AnswerAi))
    }

    @Test
    fun takeoverOff_doesNotAutoAnswer() {
        val cfg =
            NexusConfig.default().copy(
                dialerTakeover = false,
                sims = listOf(SimConfig(0, "卡1", policy = SimPolicy.AI)),
            )
        val c =
            CallPolicyController(
                loadConfig = { cfg },
                takeoverEnabled = { false },
                slotResolver = { _, _ -> 0 },
            )
        val d = c.onRinging(null, 0, "10086")
        assertTrue(d.actions.contains(PolicyAction.ShowIncomingUi))
        assertFalse(d.actions.contains(PolicyAction.AnswerAi))
    }
}
