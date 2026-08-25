package com.danceanon.native.render

import com.danceanon.native.bridge.EffectConfigDto
import com.danceanon.native.bridge.FollowConfigDto
import com.danceanon.native.tracking.TrackedPerson

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
