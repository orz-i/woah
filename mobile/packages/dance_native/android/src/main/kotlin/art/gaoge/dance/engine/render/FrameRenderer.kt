package art.gaoge.dance.engine.render

import art.gaoge.dance.engine.bridge.EffectConfigDto
import art.gaoge.dance.engine.bridge.FollowConfigDto
import art.gaoge.dance.engine.tracking.TrackedPerson

interface FrameRenderer : AutoCloseable {
    fun initialize(width: Int, height: Int)
    fun render(
        frameTexture: Int,
        persons: List<TrackedPerson>,
        selectedPersonIds: Set<Int>,
        effects: EffectConfigDto,
        follow: FollowConfigDto,
        presentationTimeUs: Long
    )
    override fun close()
}
