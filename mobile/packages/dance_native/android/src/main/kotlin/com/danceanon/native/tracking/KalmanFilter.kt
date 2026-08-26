package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import kotlin.math.max

/**
 * Standard 8-dimensional Kalman Filter for bounding box tracking (XYAH state space).
 * State: [cx, cy, a, h, vx, vy, va, vh]
 * Measurement: [cx, cy, a, h]
 *
 * Implements standard linear state transition F(dt) and measurement H with full covariance
 * propagation P = F P F^T + Q and P = (I - K H) P.
 */
class KalmanFilter {

    // 8-dim state: [cx, cy, a, h, vx, vy, va, vh]
    val state = FloatArray(8)
    val covariance = Array(8) { FloatArray(8) }

    private var lastTimestampUs: Long = -1L

    private val stdWeightPosition = 1f / 20f
    private val stdWeightVelocity = 1f / 10f

    fun init(bbox: FloatRect, timestampUs: Long = -1L) {
        val w = bbox.width
        val h = max(1e-4f, bbox.height)
        val cx = bbox.centerX
        val cy = bbox.centerY
        val a = if (h > 0) w / h else 1f

        state[0] = cx
        state[1] = cy
        state[2] = a
        state[3] = h
        state[4] = 0f
        state[5] = 0f
        state[6] = 0f
        state[7] = 0f

        lastTimestampUs = timestampUs

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
            1e-2f,
            10 * stdWeightVelocity * h
        )
        for (i in 0 until 8) {
            covariance[i][i] = std[i] * std[i]
        }
    }

    /**
     * Predict next state given elapsed time dt (or compute from timestampUs).
     */
    fun predict(timestampUs: Long = -1L): FloatRect {
        val dt = if (timestampUs > 0 && lastTimestampUs > 0 && timestampUs > lastTimestampUs) {
            ((timestampUs - lastTimestampUs) / 1_000_000f).coerceIn(0.001f, 0.2f)
        } else {
            1.0f / 30.0f
        }
        if (timestampUs > 0) {
            lastTimestampUs = timestampUs
        }

        // 1. Mean prediction: mean = F(dt) * mean
        state[0] += dt * state[4]
        state[1] += dt * state[5]
        state[2] += dt * state[6]
        state[3] += dt * state[7]

        // 2. Process noise Q(dt)
        val h = max(1e-4f, state[3])
        val stdPos = stdWeightPosition * h
        val stdVel = stdWeightVelocity * h
        val qStd = floatArrayOf(
            stdPos * dt,
            stdPos * dt,
            1e-2f * dt,
            stdPos * dt,
            stdVel * dt,
            stdVel * dt,
            1e-3f * dt,
            stdVel * dt
        )
        val q = FloatArray(8) { i -> qStd[i] * qStd[i] }

        // 3. Covariance prediction: P = F * P * F^T + Q
        // F = [ I_4   dt*I_4 ]
        //     [ 0_4    I_4   ]
        // Let P = [ P11  P12 ]
        //         [ P21  P22 ]
        // New P11 = P11 + dt*(P21 + P12) + dt^2 * P22 + Q_pos
        // New P12 = P12 + dt * P22
        // New P21 = P21 + dt * P22
        // New P22 = P22 + Q_vel
        val newP = Array(8) { FloatArray(8) }
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                val p11 = covariance[i][j]
                val p12 = covariance[i][j + 4]
                val p21 = covariance[i + 4][j]
                val p22 = covariance[i + 4][j + 4]

                newP[i][j] = p11 + dt * (p21 + p12) + dt * dt * p22
                newP[i][j + 4] = p12 + dt * p22
                newP[i + 4][j] = p21 + dt * p22
                newP[i + 4][j + 4] = p22
            }
        }
        for (i in 0 until 8) {
            newP[i][i] += q[i]
            for (j in 0 until 8) {
                covariance[i][j] = newP[i][j]
            }
        }

        return toBBox()
    }

    /**
     * Update state with new detection measurement.
     */
    fun update(bbox: FloatRect, timestampUs: Long = -1L) {
        if (timestampUs > 0) {
            lastTimestampUs = timestampUs
        }

        val w = bbox.width
        val h = max(1e-4f, bbox.height)
        val cx = bbox.centerX
        val cy = bbox.centerY
        val a = if (h > 0) w / h else 1f

        val z = floatArrayOf(cx, cy, a, h)

        // 1. Measurement noise R
        val rStd = floatArrayOf(
            stdWeightPosition * h,
            stdWeightPosition * h,
            1e-1f,
            stdWeightPosition * h
        )
        val rMat = FloatArray(4) { i -> rStd[i] * rStd[i] }

        // 2. Innovation: y = z - H * mean
        val innovation = FloatArray(4) { i -> z[i] - state[i] }

        // 3. Innovation covariance: S = H * P * H^T + R = P11 + R
        val sMat = Array(4) { FloatArray(4) }
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                sMat[i][j] = covariance[i][j]
            }
            sMat[i][i] += rMat[i]
        }

        // 4. Invert 4x4 matrix S
        val sInv = invert4x4(sMat) ?: return

        // 5. Kalman gain: K = P * H^T * S^-1 = [P11; P21] * S^-1 (size: 8x4)
        val kMat = Array(8) { FloatArray(4) }
        for (i in 0 until 8) {
            for (j in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += covariance[i][k] * sInv[k][j]
                }
                kMat[i][j] = sum
            }
        }

        // 6. Updated state: mean = mean + K * innovation
        for (i in 0 until 8) {
            var delta = 0f
            for (j in 0 until 4) {
                delta += kMat[i][j] * innovation[j]
            }
            state[i] += delta
        }

        // 7. Updated covariance: P = (I - K * H) * P = P - K * [P11  P12]
        // K * H * P: (8x4) * (4x8) = (8x8)
        val khp = Array(8) { FloatArray(8) }
        for (i in 0 until 8) {
            for (j in 0 until 8) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += kMat[i][k] * covariance[k][j]
                }
                khp[i][j] = sum
            }
        }
        for (i in 0 until 8) {
            for (j in 0 until 8) {
                covariance[i][j] -= khp[i][j]
            }
        }
    }

    fun toBBox(): FloatRect {
        val cx = state[0]
        val cy = state[1]
        val a = max(0.01f, state[2])
        val h = max(0.01f, state[3])
        val w = a * h

        return FloatRect(
            left = cx - w / 2f,
            top = cy - h / 2f,
            right = cx + w / 2f,
            bottom = cy + h / 2f
        )
    }

    companion object {
        /**
         * Computes inverse of 4x4 matrix using Gauss-Jordan elimination.
         */
        fun invert4x4(matrix: Array<FloatArray>): Array<FloatArray>? {
            val n = 4
            val a = Array(n) { r -> FloatArray(2 * n) { c ->
                if (c < n) matrix[r][c] else if (c - n == r) 1f else 0f
            } }

            for (i in 0 until n) {
                // Pivot
                var maxRow = i
                for (k in i + 1 until n) {
                    if (kotlin.math.abs(a[k][i]) > kotlin.math.abs(a[maxRow][i])) {
                        maxRow = k
                    }
                }
                val temp = a[i]
                a[i] = a[maxRow]
                a[maxRow] = temp

                val pivot = a[i][i]
                if (kotlin.math.abs(pivot) < 1e-9f) {
                    return null // Singular matrix
                }

                for (j in 0 until 2 * n) {
                    a[i][j] /= pivot
                }

                for (k in 0 until n) {
                    if (k != i) {
                        val factor = a[k][i]
                        for (j in 0 until 2 * n) {
                            a[k][j] -= factor * a[i][j]
                        }
                    }
                }
            }

            val inv = Array(n) { r ->
                FloatArray(n) { c -> a[r][c + n] }
            }
            return inv
        }
    }
}
