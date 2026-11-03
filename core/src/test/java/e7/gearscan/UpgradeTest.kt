package e7.gearscan

import e7.gearscan.data.*
import e7.gearscan.opt.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpgradeTest {
    private fun piece(
        id: Long = 1,
        enhance: Int = 0,
        speed: Double = 3.0,
        rank: String = "Epic",
        slot: Slot = Slot.HELMET,
        set: String = "SpeedSet",
    ) = Item(
        id,
        slot,
        set,
        rank,
        85,
        enhance,
        if (slot == Slot.WEAPON) GearStat.ATK else GearStat.HP,
        540.0,
        listOf(Substat(GearStat.SPD, speed, 1, false), Substat(GearStat.HP_P, 5.0, 1, false), Substat(GearStat.CR, 4.0, 1, false)) +
            if (rank == "Epic" ||
                enhance >= 12
            ) {
                listOf(Substat(GearStat.CD, 5.0, 1, false))
            } else {
                emptyList()
            },
        false,
        null,
        main15 = 2700.0,
    )

    @Test fun correctingAStepMovesItsRollToTheStatThatWentUp() {
        // recorded Crit by mistake, then corrected to the Speed roll the game showed
        val wrong = UpgradeRecord(99, listOf(piece())).record(3, 864.0, listOf(3.0, 5.0, 8.0, 5.0))
        assertEquals(2, wrong.item.subs[2].rolls)
        val fixed = wrong.record(3, 864.0, listOf(7.0, 5.0, 4.0, 5.0), correction = true)
        assertEquals(2, fixed.item.subs[0].rolls)
        assertEquals(1, fixed.item.subs[2].rolls)
    }

    @Test fun observationsRoundTripAndUndoWithoutChangingTheScan() {
        val original = piece()
        val r = UpgradeRecord(99, listOf(original)).record(3, 864.0, listOf(7.0, 5.0, 4.0, 5.0))
        assertEquals(3, r.item.enhance)
        assertEquals(7.0, r.item.subs[0].value, 0.0)
        assertEquals(0, original.enhance)
        assertEquals(3.0, original.subs[0].value, 0.0)
        assertEquals(2, r.item.subs[0].rolls)
        val restored = UpgradeRecord.fromJson(JSONObject(r.toJson().toString()))
        assertEquals(2700.0, restored.item.main15, 1e-6)
        assertEquals(99L, restored.scan)
        assertEquals(
            3.0,
            restored
                .undo()
                .item.subs[0]
                .value,
            0.0,
        )
    }

    @Test fun fribbelsPlus15MainIsRestoredToTheCurrentEnhancement() {
        val scan = piece(enhance = 9)
        val current = UpgradeRecord.fromScan(scan)
        assertEquals(1512.0, current.mainValue, 0.0)
        assertEquals(1944.0, UpgradeRecord.mainAt(current, 12), 0.0)
        assertEquals(2700.0, UpgradeRecord.mainAt(current, 15), 0.0)
        assertEquals(540.0, scan.mainValue, 0.0)
    }

    @Test fun rejectsMissingRollsAndTwoChangedLines() {
        val r = UpgradeRecord(1, listOf(piece()))

        fun rejects(v: List<Double>) {
            assertTrue(runCatching { r.record(3, 864.0, v) }.isFailure)
        }
        rejects(listOf(3.0, 5.0, 4.0, 5.0))
        rejects(listOf(7.0, 9.0, 4.0, 5.0))
        rejects(listOf(Double.NaN, 5.0, 4.0, 5.0))
        assertTrue(runCatching { r.record(6, 864.0, listOf(7.0, 5.0, 4.0, 5.0)) }.isFailure)
    }

    @Test fun heroicRevealsAFourthLineAt12AndCanFinish() {
        val r = UpgradeRecord(1, listOf(piece(enhance = 9, rank = "Heroic")))
        assertTrue(runCatching { r.record(12, 1944.0, listOf(3.0, 5.0, 4.0)) }.isFailure)
        val at12 = r.record(12, 1944.0, listOf(3.0, 5.0, 4.0), GearStat.EFF to 7.0)
        assertEquals(4, at12.item.subs.size)
        assertTrue(runCatching { r.record(12, 1944.0, listOf(3.0, 5.0, 4.0), GearStat.HP to 160.0) }.isFailure)
        val at15 = at12.record(15, 2700.0, listOf(7.0, 5.0, 4.0, 7.0))
        assertEquals(2700.0, at15.item.main15, 0.0)
    }

    @Test fun partialEnhancementStartsAtTheNextMultipleOfThree() {
        val at2 = piece(enhance = 2)
        val sim = Finish.of(at2, 10)
        assertTrue(sim.all { it.subs.sumOf { s -> s.rolls } == 9 })
        assertEquals(3, UpgradeRecord(1, listOf(at2)).record(3, 864.0, listOf(7.0, 5.0, 4.0, 5.0)).item.enhance)
    }

    private val content get() =
        Content(
            "test",
            JSONObject()
                .put("images", JSONObject().put("face", "").put("artifact", ""))
                .put("builds", "")
                .put("heroes", JSONArray())
                .put("artifacts", JSONArray())
                .put(
                    "sets",
                    JSONArray(
                        listOf("SpeedSet", "HitSet").map { key ->
                            JSONObject()
                                .put("key", key)
                                .put("name", key)
                                .put(
                                    "pieces",
                                    if (key ==
                                        "SpeedSet"
                                    ) {
                                        4
                                    } else {
                                        2
                                    },
                                ).put("icon", "")
                        },
                    ),
                ),
        )

    private fun setup(
        items: List<Item>,
        request: Request =
            Request().apply {
                goal = Goal.SPEED
                capCrit = false
                minEnhance = 0
                lowerPriorityOnly =
                    false
            },
    ): Setup {
        val b = Box(JSONObject().put("items", JSONArray(items.map(UpgradeRecord::itemJson))).put("heroes", JSONArray()))
        val h = OwnedHero(99, "test", "Test hero", 6)
        val g =
            GameHero(
                "test",
                "Test hero",
                null,
                null,
                Stats8(1000.0, 5000.0, 500.0, 100.0, 15.0, 150.0, 0.0, 0.0),
                null,
                null,
                0.0,
                emptyList(),
            )
        return Setup(h, g, HeroConfig().apply { this.request = request }, request, b, content, { true })
    }

    private fun gear() = Slot.entries.mapIndexed { i, slot -> piece(i + 10L, 15, 10.0, slot = slot) }

    @Test fun aStrongFinishImprovesACompleteBuildAndResumeKeepsTheResult() {
        val q = piece(1, 15, 30.0)
        val setup = setup(gear() + q)
        val check = UpgradeCheck()
        val r = check.check(q, listOf(setup)).single()
        assertTrue(r.current)
        assertEquals(listOf(0), r.hits)
        assertEquals(0, r.unknown)
        assertFalse(r.provisional)
        assertEquals(20.0 / Objectives.rollSize(setup.hb)[3], r.gain!!, 0.01)
        var checkpoints = 0
        val resumed = check.check(q, listOf(setup), listOf(r), saved = { checkpoints++ })
        assertEquals(0, checkpoints)
        assertEquals(r, resumed.single())
    }

    @Test fun aStatUpgradeThatBreaksRequiredSetsDoesNotCount() {
        val q = piece(1, 15, 30.0, set = "HitSet")
        val req =
            Request().apply {
                goal = Goal.SPEED
                capCrit = false
                minEnhance = 0
                setAlts = listOf(mapOf("SpeedSet" to 6))
            }
        val r = UpgradeCheck().check(q, listOf(setup(gear() + q, req))).single()
        assertFalse(r.current)
        assertTrue(r.hits.isEmpty())
    }

    @Test fun incompleteGearIsNotACompleteBuildUpgrade() {
        val q = piece(1, 15, 30.0)
        val r = UpgradeCheck().check(q, listOf(setup(listOf(q)))).single()
        assertFalse(r.current)
        assertTrue(r.hits.isEmpty())
    }

    @Test fun extraSpeedAboveTargetDoesNotExcuseLosingHealthBelowTarget() {
        val gear = gear()
        val old = piece(1, 15, 30.0)
        val q =
            Item(
                old.id,
                old.slot,
                old.set,
                old.rank,
                old.level,
                old.enhance,
                old.mainType,
                old.mainValue,
                old.subs.map { if (it.type == GearStat.HP_P) Substat(it.type, 1.0, 1, false) else it },
                false,
                null,
            )
        val req =
            Request().apply {
                goal = Goal.TARGETS
                capCrit = false
                minEnhance = 0
                reforged = false
                weights[1] = 3
                weights[3] = 3
            }
        val s = setup(gear + q, req)
        req.targets[1] =
            e7.gearscan.calc.Calc
                .final(s.hb, gear, false, s.content)
                .stats[1]
        req.targets[3] = 160.0
        val r = UpgradeCheck().check(q, listOf(s)).single()
        assertFalse(r.current)
        assertTrue(r.hits.isEmpty())
    }

    @Test fun zeroTimeChecksAreUncertainAndNeverPauseWithCertainty() {
        val q = piece(1, 0, 3.0)
        val r = UpgradeCheck(heroMs = 0).check(q, listOf(setup(gear() + q))).single()
        assertTrue(r.unknown > 0)
    }

    @Test fun twoUnfinishedPiecesCanUnlockABuildTogether() {
        val q = piece(1, 0, 3.0, set = "HitSet")
        val partner = piece(2, 0, 3.0, slot = Slot.BOOTS, set = "HitSet")
        val req =
            Request().apply {
                goal = Goal.SPEED
                capCrit = false
                minEnhance = 15
                setAlts =
                    listOf(mapOf("SpeedSet" to 4, "HitSet" to 2))
            }
        val r = UpgradeCheck().check(q, listOf(setup(gear() + q + partner, req))).single()
        assertFalse(r.current)
        assertTrue(r.hits.isEmpty())
        assertTrue(r.combo)
    }

    @Test fun partnersImprovementIsNotCreditedToAWeakTrackedPiece() {
        val q = piece(1, 12, 1.0)
        val partner = piece(2, 0, 20.0, slot = Slot.BOOTS)
        val req =
            Request().apply {
                goal = Goal.SPEED
                capCrit = false
                minEnhance = 15
            }
        val r = UpgradeCheck().check(q, listOf(setup(gear() + q + partner, req))).single()
        assertFalse(r.current)
        assertTrue(r.hits.isEmpty())
        assertFalse(r.combo)
    }

    @Test fun alreadyMeetingTheTargetIsNotAReasonToKeepSpending() {
        val q = piece(1, 12, 30.0)
        val req =
            Request().apply {
                goal = Goal.TARGETS
                capCrit = false
                minEnhance = 15
                reforged = false
                targets[3] = 170.0
                weights[3] =
                    3
            }
        val r = UpgradeCheck().check(q, listOf(setup(gear() + q, req))).single()
        assertTrue(r.current)
        assertTrue(r.hits.isEmpty())
        assertFalse(r.combo)
    }
}
