package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.NativeMask

/**
 * Debug-only aggregate evidence about one conservative-unknown SELECTED person
 * overlapping a genuinely observed UNSELECTED person. Does not change masks.
 *
 * No video pixels, RGB, facial landmarks, binary per-pixel arrays or raw masks
 * enter the output. Counts are aggregated into 4x4 bins over the selected
 * detection's source-space bbox. These are NOT ground-truth person ownership.
 */
object PrivacyUnknownForegroundPixelAudit {
    private const val GRID = 4
    private const val MASK_THRESHOLD = 128
    private const val VISIBLE_THRESHOLD = 39
    private const val RAW_ADVANTAGE_BYTE = 26 // ceil(0.10 * 255)

    data class Probe(
        val targetBbox: FloatRect,
        val selectedRaw: NativeMask,
        val foregroundFresh: NativeMask,
        val foregroundObserved: NativeMask?,
        val ownership: NativeMask?,
        val erodedFreshCore: NativeMask?,
        val approvedCarve: NativeMask?,
        val temporalCarve: NativeMask?,
        val effectiveSelected: NativeMask,
        val mergedPrivacy: NativeMask,
        val competingSelected: List<NativeMask>,
        val strongVetoSelected: List<NativeMask> = emptyList()
    )

    private fun matching(mask: NativeMask?, reference: NativeMask): Boolean {
        if (mask == null) return true
        return mask.width == reference.width && mask.height == reference.height &&
            mask.originalWidth == reference.originalWidth &&
            mask.originalHeight == reference.originalHeight &&
            mask.samplingRect == reference.samplingRect &&
            mask.mapper == reference.mapper &&
            mask.buffer.capacity().toLong() >=
                mask.width.toLong() * mask.height.toLong()
    }

    fun summarize(probe: Probe): Map<String, Any?> {
        val selected = probe.selectedRaw
        val width = selected.width
        val height = selected.height
        val count = width.toLong() * height.toLong()
        if (width <= 0 || height <= 0 || count > Int.MAX_VALUE ||
            selected.buffer.capacity().toLong() < count
        ) return mapOf("valid" to false, "reason" to "INVALID_SELECTED_MASK")
        val masks = listOf(
            probe.foregroundFresh, probe.foregroundObserved, probe.ownership,
            probe.erodedFreshCore, probe.approvedCarve, probe.temporalCarve,
            probe.effectiveSelected, probe.mergedPrivacy
        ) + probe.competingSelected + probe.strongVetoSelected
        if (masks.any { !matching(it, selected) }) {
            return mapOf("valid" to false, "reason" to "MASK_COORDINATE_MISMATCH")
        }

        val mapper = selected.mapper ?: ModelCoordinateMapper(
            srcWidth = selected.originalWidth.coerceAtLeast(1),
            srcHeight = selected.originalHeight.coerceAtLeast(1),
            protoSize = width
        )
        val box = probe.targetBbox
        val left = mapper.sourceToProtoX(box.left).coerceIn(0f, width.toFloat())
        val top = mapper.sourceToProtoY(box.top).coerceIn(0f, height.toFloat())
        val right = mapper.sourceToProtoX(box.right).coerceIn(0f, width.toFloat())
        val bottom = mapper.sourceToProtoY(box.bottom).coerceIn(0f, height.toFloat())
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() ||
            !bottom.isFinite() || right <= left || bottom <= top
        ) return mapOf("valid" to false, "reason" to "INVALID_SELECTED_BBOX")

        // Fixed schema is easier to diff between horizontal/portrait exports.
        // Every count except "fresh_foreground"/"final_merged" and
        // "competing_selected" is restricted to selected raw pixels.
        val keys = listOf(
            "selected_raw",
            "fresh_foreground",
            "selected_and_fresh_foreground",
            "selected_and_observed_foreground",
            "selected_with_weak_foreground",
            "selected_with_raw_foreground_advantage",
            "selected_and_probability_ownership",
            "selected_and_fresh_eroded_core",
            "selected_and_approved_carve",
            "selected_and_temporal_carve",
            "selected_retained_effective",
            "selected_retained_and_fresh_foreground",
            "selected_retained_with_weak_foreground",
            "competing_selected",
            "competing_selected_and_fresh_foreground",
            "final_merged",
            "final_merged_and_fresh_foreground"
        )
        val bins = keys.associateWith { IntArray(GRID * GRID) }
        fun byte(mask: NativeMask?, index: Int): Int =
            if (mask == null) 0 else mask.buffer.get(index).toInt() and 0xFF

        // Iterate only the selected person's bbox, not the entire video.
        val fromX = left.toInt().coerceAtLeast(0)
        val fromY = top.toInt().coerceAtLeast(0)
        val toX = kotlin.math.ceil(right.toDouble()).toInt().coerceAtMost(width)
        val toY = kotlin.math.ceil(bottom.toDouble()).toInt().coerceAtMost(height)
        for (y in fromY until toY) {
            val py = y + 0.5f
            if (py < top || py >= bottom) continue
            val row = ((py - top) * GRID / (bottom - top)).toInt().coerceIn(0, GRID - 1)
            for (x in fromX until toX) {
                val px = x + 0.5f
                if (px < left || px >= right) continue
                val col = ((px - left) * GRID / (right - left)).toInt().coerceIn(0, GRID - 1)
                val bin = row * GRID + col
                val pos = y * width + x
                val selectedByte = byte(selected, pos)
                val frontByte = byte(probe.foregroundFresh, pos)
                val chosen = selectedByte >= MASK_THRESHOLD
                fun mark(key: String, flag: Boolean) {
                    if (flag) bins.getValue(key)[bin]++
                }
                mark("selected_raw", chosen)
                mark("fresh_foreground", frontByte >= MASK_THRESHOLD)
                mark("selected_and_fresh_foreground", chosen && frontByte >= MASK_THRESHOLD)
                mark(
                    "selected_and_observed_foreground",
                    chosen && byte(probe.foregroundObserved, pos) >= MASK_THRESHOLD
                )
                mark(
                    "selected_with_weak_foreground",
                    chosen && frontByte >= VISIBLE_THRESHOLD && frontByte < MASK_THRESHOLD
                )
                mark(
                    "selected_with_raw_foreground_advantage",
                    chosen && frontByte - selectedByte >= RAW_ADVANTAGE_BYTE
                )
                mark(
                    "selected_and_probability_ownership",
                    chosen && byte(probe.ownership, pos) >= MASK_THRESHOLD
                )
                mark(
                    "selected_and_fresh_eroded_core",
                    chosen && byte(probe.erodedFreshCore, pos) >= MASK_THRESHOLD
                )
                mark(
                    "selected_and_approved_carve",
                    chosen && byte(probe.approvedCarve, pos) >= MASK_THRESHOLD
                )
                mark(
                    "selected_and_temporal_carve",
                    chosen && byte(probe.temporalCarve, pos) >= MASK_THRESHOLD
                )
                val retained = chosen &&
                    byte(probe.effectiveSelected, pos) >= MASK_THRESHOLD
                val competing = probe.competingSelected.any {
                    byte(it, pos) >= MASK_THRESHOLD
                }
                val merged = byte(probe.mergedPrivacy, pos) >= MASK_THRESHOLD
                mark("selected_retained_effective", retained)
                mark(
                    "selected_retained_and_fresh_foreground",
                    retained && frontByte >= MASK_THRESHOLD
                )
                mark(
                    "selected_retained_with_weak_foreground",
                    retained && frontByte in VISIBLE_THRESHOLD until MASK_THRESHOLD
                )
                mark("competing_selected", competing)
                mark(
                    "competing_selected_and_fresh_foreground",
                    competing && frontByte >= MASK_THRESHOLD
                )
                mark("final_merged", merged)
                mark(
                    "final_merged_and_fresh_foreground",
                    merged && frontByte >= MASK_THRESHOLD
                )
            }
        }
        // Model instance masks can extend beyond a detected bbox. Reporting
        // only the boxed 4x4 grid would hide precisely that type of bleed.
        // Keep the outside evidence as five aggregate totals, never a map.
        var outsideSelected = 0
        var outsideSelectedAndFresh = 0
        var outsideSelectedRetained = 0
        var outsideApproved = 0
        var outsideTemporal = 0
        for (y in 0 until height) {
            val py = y + 0.5f
            for (x in 0 until width) {
                val px = x + 0.5f
                if (px >= left && px < right && py >= top && py < bottom) {
                    continue
                }
                val index = y * width + x
                if (byte(selected, index) < MASK_THRESHOLD) continue
                outsideSelected++
                if (byte(probe.foregroundFresh, index) >= MASK_THRESHOLD) {
                    outsideSelectedAndFresh++
                }
                if (byte(probe.effectiveSelected, index) >= MASK_THRESHOLD) {
                    outsideSelectedRetained++
                }
                if (byte(probe.approvedCarve, index) >= MASK_THRESHOLD) {
                    outsideApproved++
                }
                if (byte(probe.temporalCarve, index) >= MASK_THRESHOLD) {
                    outsideTemporal++
                }
            }
        }
        // Same pixel thresholds as StrongObservedForegroundCarve.build.
        // Count evidence over the ENTIRE selected proto mask, including pixels
        // outside its bbox. These are aggregate integers, never mask bytes or
        // pixel coordinates. This is diagnostic-only; no predicate below can
        // authorize a removal of privacy.
        var selectedFreshStrong224 = 0
        var selectedObservedStrong224 = 0
        var selectedBothStrong192 = 0
        var selectedBothStrong224 = 0
        var bothStrong224Vetoed = 0
        for (i in 0 until count.toInt()) {
            if (byte(selected, i) < MASK_THRESHOLD) continue
            val fresh = byte(probe.foregroundFresh, i)
            val observed = byte(probe.foregroundObserved, i)
            if (fresh >= 224) selectedFreshStrong224++
            if (observed >= 224) selectedObservedStrong224++
            if (fresh >= 192 && observed >= 192) selectedBothStrong192++
            if (fresh >= 224 && observed >= 224) {
                selectedBothStrong224++
                if (probe.strongVetoSelected.any {
                    byte(it, i) >= MASK_THRESHOLD
                }) bothStrong224Vetoed++
            }
        }
        val summaries = keys.associateWith { key -> bins.getValue(key).sum() }
        return mapOf(
            "valid" to true,
            "evidence_scope" to "AGGREGATE_PROTO_MASK_COUNTS_ONLY_NO_RGB_NO_RAW_MASK",
            "grid_shape" to listOf(GRID, GRID),
            "grid_order" to "ROW_MAJOR_TOP_TO_BOTTOM_SOURCE",
            "selected_bbox_proto" to listOf(left, top, right, bottom),
            "total_by_signal" to summaries,
            "grid_by_signal" to bins.mapValues { (_, values) -> values.toList() },
            "outside_selected_bbox_totals" to mapOf(
                "selected_raw" to outsideSelected,
                "selected_and_fresh_foreground" to outsideSelectedAndFresh,
                "selected_retained_effective" to outsideSelectedRetained,
                "selected_and_approved_carve" to outsideApproved,
                "selected_and_temporal_carve" to outsideTemporal
            ),
            "strong_threshold_totals" to mapOf(
                "selected_and_fresh_ge224" to selectedFreshStrong224,
                "selected_and_observed_ge224" to selectedObservedStrong224,
                "selected_and_both_ge192" to selectedBothStrong192,
                "selected_and_both_ge224" to selectedBothStrong224,
                "selected_and_both_ge224_vetoed_other" to bothStrong224Vetoed,
                "selected_and_both_ge224_without_other" to
                    selectedBothStrong224 - bothStrong224Vetoed
            ),
            "strong_veto_selected_mask_count" to probe.strongVetoSelected.size,
            "competing_selected_count" to probe.competingSelected.size,
            "not_ground_truth_ownership" to true
        )
    }
}
