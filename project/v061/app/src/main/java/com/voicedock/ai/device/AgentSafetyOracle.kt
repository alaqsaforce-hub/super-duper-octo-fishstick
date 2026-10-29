package com.voicedock.ai.device

import com.voicedock.ai.agent.AgentSafety
import com.voicedock.ai.context.SafetyOracle

/**
 * Production [SafetyOracle]: delegates to the already unit-tested v0.5 AgentSafety rules,
 * so the screen-context probe and the on-screen agent share ONE safety vocabulary
 * (finance packages/labels, PIN/OTP/card-like text, blocked packages).
 */
object AgentSafetyOracle : SafetyOracle {
    override fun blockedPackage(pkg: String): Boolean =
        AgentSafety.isFinance(pkg) || AgentSafety.isBlockedPackage(pkg, emptySet())

    override fun financeLabel(label: String): Boolean = AgentSafety.isFinanceLabel(label)

    override fun secretText(text: String): Boolean = AgentSafety.looksSecret(text)
}
