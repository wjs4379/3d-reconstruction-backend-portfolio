package com.hanyang.backend.domain.model3d

/** Shared finite bounds for AI polling and callback results. */
object AiPayloadLimits {
    const val SOURCE_IMAGE_COUNT = 4
    const val MAX_RESULT_BYTES = 16 * 1024 * 1024
    const val MAX_VERTICES = 10_000
    const val MAX_EDGES = 20_000
}
