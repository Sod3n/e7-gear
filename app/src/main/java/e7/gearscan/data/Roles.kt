package e7.gearscan.data

import e7.gearscan.calc.HeroBase

/** One-tap starting builds for heroes with no community build: sets, mains and targets from a role. */
enum class Role(val label: String, val sub: String) {
    DAMAGE("Damage", "Crit, attack, speed"),
    SPEED("Speed", "Speed first, then hit"),
    TANK("Tank", "Health, defense"),
    SUPPORT("Support", "Speed, eff. resist");

    fun request(hb: HeroBase, keep: Request?): Request {
        val r = keep?.copy() ?: Request()
        for (x in 0 until 8) { r.targets[x] = Double.NaN; r.weights[x] = 0; r.mins[x] = Double.NaN; r.fromPreset[x] = false }
        fun t(s: Stat, v: Double, w: Int) { r.targets[s.ordinal] = v; r.weights[s.ordinal] = w; r.fromPreset[s.ordinal] = true }
        // flat stats scale with the hero's own base, rounded to tens
        fun base(s: Stat, k: Double) = Math.round(hb.base[s] * k / 10.0) * 10.0
        val (sets, mains) = when (this) {
            DAMAGE -> {
                t(Stat.CR, 100.0, 3); t(Stat.CD, 300.0, 2); t(Stat.ATK, base(Stat.ATK, 3.5), 1); t(Stat.SPD, 200.0, 1)
                r.mins[Stat.CR.ordinal] = 100.0
                SetChoice(listOf("DestructionSet", "AttackSet"), mapOf("CriticalSet" to 1)) to
                    mapOf(Slot.NECKLACE to setOf(GearStat.CD), Slot.RING to setOf(GearStat.ATK_P), Slot.BOOTS to setOf(GearStat.SPD))
            }
            SPEED -> {
                t(Stat.SPD, 280.0, 3); t(Stat.EFF, 100.0, 2); t(Stat.HP, base(Stat.HP, 2.0), 1)
                SetChoice(listOf("SpeedSet"), mapOf("HitSet" to 1, "ImmunitySet" to 1)) to
                    mapOf(Slot.NECKLACE to setOf(GearStat.HP_P), Slot.RING to setOf(GearStat.EFF, GearStat.HP_P), Slot.BOOTS to setOf(GearStat.SPD))
            }
            TANK -> {
                t(Stat.HP, base(Stat.HP, 3.2), 3); t(Stat.DEF, base(Stat.DEF, 2.5), 2); t(Stat.SPD, 180.0, 1)
                SetChoice.anyPairs(listOf("HealthSet", "DefenseSet", "ImmunitySet"), 3) to
                    mapOf(Slot.NECKLACE to setOf(GearStat.HP_P, GearStat.DEF_P), Slot.RING to setOf(GearStat.HP_P, GearStat.DEF_P),
                        Slot.BOOTS to setOf(GearStat.SPD, GearStat.HP_P))
            }
            SUPPORT -> {
                t(Stat.SPD, 260.0, 3); t(Stat.RES, 120.0, 2); t(Stat.HP, base(Stat.HP, 2.2), 1)
                SetChoice(listOf("SpeedSet"), mapOf("ImmunitySet" to 1, "ResistSet" to 1)) to
                    mapOf(Slot.NECKLACE to setOf(GearStat.HP_P), Slot.RING to setOf(GearStat.RES, GearStat.HP_P), Slot.BOOTS to setOf(GearStat.SPD))
            }
        }
        r.setAlts = sets.toAlts(); r.mains = mains; r.goal = Goal.TARGETS
        r.presetLabel = "Role · $label"; r.presetAuto = false; r.presetShare = 0
        return r
    }
}
