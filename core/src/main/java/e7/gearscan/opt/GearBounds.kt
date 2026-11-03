package e7.gearscan.opt

import e7.gearscan.calc.Reforge
import e7.gearscan.data.*

/** Bounds for the new gear controls, from the same roll and reforge model as the forecast.
 * They include rare maximum rolls; ordinary-roll probabilities remain separate. */
object GearBounds {
    data class Limits(
        val minimum: Double,
        val maximum: Double,
    ) {
        fun contains(value: Double) = value.isFinite() && value >= minimum - 1e-8 && value <= maximum + 1e-8
    }

    /** A minimum is a threshold, so 1 is legal even if the first roll of a stat is larger. */
    fun target(
        type: GearStat,
        reforge: Boolean,
        includeFinished: Boolean = false,
    ): Limits {
        val raw85 = 6 * Finish.maximumRoll(type, 85).toDouble()
        val raw88 = 6 * Finish.maximumRoll(type, 88).toDouble()
        val top85 = raw85 + if (reforge || includeFinished) Reforge.gain(type, 6) else 0.0
        return Limits(1.0, maxOf(top85, raw88))
    }

    fun newLine(
        type: GearStat,
        level: Int,
    ) = Limits(Finish.range(type, level).first.toDouble(), Finish.maximumRoll(type, level).toDouble())

    fun existingLine(
        item: Item,
        index: Int,
        enhance: Int,
        correction: Boolean,
    ): Limits {
        val s = item.subs[index]
        if (correction) {
            val rolls = s.rolls.coerceAtLeast(1)
            // the scanned value wins for preset/modded gear; a correction may move one roll between lines
            return Limits(
                minOf(s.value, (rolls - 1).coerceAtLeast(1) * Finish.range(s.type, item.level).first.toDouble()),
                maxOf(s.value, (rolls + 1) * Finish.maximumRoll(s.type, item.level).toDouble()),
            )
        }
        val reveal = item.rank == "Heroic" && item.subs.size == 3 && enhance == 12
        return if (reveal) {
            Limits(s.value, s.value)
        } else {
            Limits(
                s.value + Finish.range(s.type, item.level).first,
                s.value + Finish.maximumRoll(s.type, item.level),
            )
        }
    }

    fun main(
        record: UpgradeRecord,
        enhance: Int,
    ): Limits {
        val value = UpgradeRecord.mainAt(record.history.first(), enhance)
        return Limits(value, value)
    }
}
