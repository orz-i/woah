package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect

class KalmanFilter {

    // 8-dim state: [x, y, a, h, vx, vy, va, vh]
    // (x, y) = center, a = aspect ratio (w/h), h = height
    private val state = FloatArray(8)
    private val covariance = Array(8) { FloatArray(8) }

    private val stdWeightPosition = 1f / 20f
    private val stdWeightVelocity = 1f / 160f

    fun init(bbox: FloatRect) {
        val w = bbox.width
        val h = bbox.height
        val x = bbox.centerX
        val y = bbox.centerY
        val a = if (h > 0) w / h else 1f

        state[0] = x
        state[1] = y
        state[2] = a
        state[3] = h
        state[4] = 0f
        state[5] = 0f
        state[6] = 0f
        state[7] = 0f

        // Initialize covariance diagonal
        for (i in 0 until 8) {
            for (j in 0 until 8) {
                covariance[i][j] = 0f
            }
        }
        val std = floatArrayOf(
            2 * stdWeightPosition * h,
            2 * stdWeightPosition * h,
            1e-2f,
            2 * stdWeightPosition * h,
            10 * stdWeightVelocity * h,
            10 * stdWeightVelocity * h,
            1e-5f,
            10 * stdWeightVelocity * h
        )
        for (i in 0 until 8) {
            covariance[i][i] = std[i] * std[i]
        }
    }

    fun predict(): FloatRect {
        // Constant velocity transition: x = x + dt * vx
        state[0] += state[4]
        state[1] += state[5]
        state[2] += state[6]
        state[3] += state[7]

        // Process noise
        val h = state[3]
        val std = floatArrayOf(
            stdWeightPosition * h,
            stdWeightPosition * h,
            1e-2f,
            stdWeightPosition * h,
            stdWeightVelocity * h,
            stdWeightVelocity * h,
            1e-5f,
            stdWeightVelocity * h
        )
        for (i in 0 until 8) {
            covariance[i][i] += std[i] * std[i]
        }

        return toBBox()
    }

    fun update(bbox: FloatRect) {
        val w = bbox.width
        val h = bbox.height
        val x = bbox.centerX
        val y = bbox.centerY
        val a = if (h > 0) w / h else 1f

        val measurement = floatArrayOf(x, y, a, h)

        // Measurement noise
        val stdM = floatArrayOf(
            stdWeightPosition * h,
            stdWeightPosition * h,
            1e-1f,
            stdWeightPosition * h
        )

        // Simplified Kalman update for diagonal measurement matrix H = [I_4, 0]
        for (i in 0 until 4) {
            val r = stdM[i] * stdM[i]
            val s = covariance[i][i] + r
            val k = covariance[i][i] / (if (s != 0f) s else 1e-6f)
            val residual = measurement[i] - state[i]

            state[i] += k * residual
            state[i + 4] += (covariance[i + 4][i] / (if (s != 0f) s else 1e-6f)) * residual
            covariance[i][i] *= (1f - k)
        }
    }

    fun toBBox(): FloatRect {
        val x = state[0]
        val y = state[1]
        val a = state[2]
        val h = state[3]
        val w = a * h

        return FloatRect(
            left = x - w / 2f,
            top = y - h / 2f,
            right = x + w / 2f,
            bottom = y + h / 2f
        )
    }
}
