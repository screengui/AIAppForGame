package com.gameagent.app.runtime

import com.gameagent.core.agent.GameAgent
import com.gameagent.core.model.AgentDecision
import com.gameagent.core.model.GameState
import com.gameagent.core.perception.ImageFrame
import com.gameagent.core.perception.PerceptionProvider
import com.gameagent.core.perception.PerceptionResult

/**
 * Forwards to a swappable delegate.
 *
 * The session is constructed once with fixed collaborators, but the user can change the perception
 * source and the decision source at runtime. Rather than rebuilding the session (and losing its
 * state machine, counters and queued frames), the session holds a stable indirection whose target is
 * replaced. Both implementation and id/description reflect the *current* delegate, so the
 * dashboard always reports what is actually running.
 */
class DelegatingPerceptionProvider(
    private var delegate: PerceptionProvider,
) : PerceptionProvider {

    override val id: String get() = delegate.id

    override val description: String get() = delegate.description

    fun swap(next: PerceptionProvider) {
        delegate = next
    }

    fun current(): PerceptionProvider = delegate

    override suspend fun observe(frame: ImageFrame): PerceptionResult = delegate.observe(frame)
}

class DelegatingGameAgent(
    private var delegate: GameAgent,
) : GameAgent {

    override val id: String get() = delegate.id

    override val displayName: String get() = delegate.displayName

    override val disclosure: String get() = delegate.disclosure

    fun swap(next: GameAgent) {
        delegate = next
    }

    fun current(): GameAgent = delegate

    override suspend fun decide(observation: GameState): AgentDecision = delegate.decide(observation)
}
