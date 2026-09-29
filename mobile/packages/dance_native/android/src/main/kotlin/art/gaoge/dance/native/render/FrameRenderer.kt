package art.gaoge.dance.native.render

import art.gaoge.dance.native.bridge.EffectConfigDto
import art.gaoge.dance.native.bridge.FollowConfigDto
import art.gaoge.dance.native.tracking.TrackedPerson

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
