package e7.gearscan

import e7.gearscan.data.*
import e7.gearscan.opt.*
import e7.gearscan.overlay.UpgradeTracker
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RollReviewTest {
    private fun raw(id:Long,high:Boolean=false,family:String="cra6"):JSONObject {
        val types=listOf("speed","max_hp_rate","cri","res","speed","speed","speed","res")
        val values=if(high)listOf(3.0,.04,.03,.04,4.0,4.0,4.0,.08) else listOf(3.0,.04,.03,.04,2.0,2.0,4.0,.04)
        val stats=listOf(GearStat.SPD,GearStat.HP_P,GearStat.CR,GearStat.RES)
        return JSONObject().put("id",id).put("gear","Helmet").put("type","helm").put("set","SpeedSet").put("rank","Epic").put("level",85).put("enhance",12).put("code","ecw6h").put("mainStatId","${family}_helm_m")
            .put("main",JSONObject().put("type","Health").put("value",2700))
            .put("op",JSONArray().put(JSONArray().put("max_hp").put(540)).apply { types.indices.forEach { i->put(JSONArray().put(types[i]).put(values[i])) } })
            .put("substats",JSONArray(stats.mapIndexed { i,s->val indices=types.indices.filter { types[it]==types[i] };JSONObject().put("type",s.key).put("value",indices.sumOf { values[it] }*if(s.showsPercent)100 else 1).put("rolls",indices.size) }))
    }
    private fun context(q:SellQuery=SellQuery(reforge=false),vararg rows:JSONObject)=SellSearch.Context(Box(JSONObject().put("items",JSONArray(rows.toList())).put("heroes",JSONArray())),emptyMap(),1,emptySet(),emptySet(),emptyMap(),q)
    @Test fun defaultReviewNeedsNoScoreFloorAndSortsOverallRollQuality() {
        val ctx=context(SellQuery(reforge=false),raw(1,true),raw(2))
        val out=SellSearch.run(ctx,null)
        assertEquals(listOf(2L,1L),out.matches.map { it.item.id });assertEquals(0,out.unknown)
        assertFalse(out.matches.first().scoreUsed);assertNull(out.matches.first().goal.floor)
        assertEquals(3,RollReview.weakCount(out.matches.first().rolls));assertEquals(3,RollReview.minimumCount(out.matches.first().rolls))
        assertEquals(GearStat.SPD,RollReview.focus(out.matches.first().rolls)!!.key)
        val scored=SellSearch.run(context(SellQuery(reforge=false,scoreFilter=true),raw(2)),null)
        assertTrue(scored.matches.isEmpty());assertEquals(1,scored.unknown)
    }
    @Test fun startingRollsParticipateWhenUnupgradedPiecesAreIncluded() {
        val zero=raw(3);zero.put("enhance",0);val op=zero.getJSONArray("op");repeat(4) { op.remove(op.length()-1) }
        zero.getJSONArray("substats").getJSONObject(0).put("value",3).put("rolls",1)
        zero.getJSONArray("substats").getJSONObject(3).put("value",4).put("rolls",1)
        for(desc in listOf(false,true)) {
            val out=SellSearch.run(context(SellQuery(reforge=false,minimumProcs=0,descending=desc),zero,raw(2)),null)
            assertEquals(if(desc)listOf(2L,3L) else listOf(3L,2L),out.matches.map { it.item.id })
        }
    }
    private fun ring(id:Long,strongStart:Boolean):JSONObject {
        val start=if(strongStart)listOf(.08,.05,4.0,.07) else listOf(.04,.03,2.0,.04)
        val hits=if(strongStart)listOf(.03,3.0,3.0,.03,.04) else listOf(.04,4.0,4.0,.04,.06)
        val keys=listOf("def_rate","cri","speed","cri_dmg","cri","speed","speed","cri","def_rate")
        val values=start+hits
        val reforges=listOf("def_rate" to .03,"cri" to .03,"speed" to 2.0,"cri_dmg" to .01)
        val stats=listOf(GearStat.DEF_P,GearStat.CR,GearStat.SPD,GearStat.CD)
        return raw(id).put("gear","Ring").put("type","ring").put("mainStatId","cra7_ring_m").put("enhance",15).put("level",90)
            .put("main",JSONObject().put("type","AttackPercent").put("value",65))
            .put("op",JSONArray().put(JSONArray().put("att_rate").put(.13)).apply {
                keys.indices.forEach { i->put(JSONArray().put(keys[i]).put(values[i])) }
                reforges.forEach { (k,v)->put(JSONArray().put(k).put(v).put("u")) }
            }).put("substats",JSONArray(stats.mapIndexed { i,stat->
                val indices=keys.indices.filter { keys[it]==keys[i] }
                val value=(indices.sumOf { values[it] }+reforges[i].second)*if(stat.showsPercent)100 else 1
                JSONObject().put("type",stat.key).put("value",value).put("rolls",indices.size)
            }))
    }
    @Test fun excellentStartsOutrankBetterUpgradeOnlyProcsAndTheRingIsThreeOfNine() {
        val q=SellQuery(reforge=false,maximumEnhance=15)
        val ctx=context(q,ring(1,true),ring(2,false));val out=SellSearch.run(ctx,null)
        val good=out.rows.first { it.item.id==1L };val worse=out.rows.first { it.item.id==2L }
        assertEquals(9,good.rolls!!.size);assertEquals(3,RollReview.overallWeakCount(good.rolls))
        assertEquals(5,RollReview.upgrades(good.rolls)!!.size);assertEquals(3,RollReview.weakCount(good.rolls))
        assertEquals(20.0,good.quality.proc!!,1e-9);assertEquals(500.0/9,good.quality.total!!,1e-9)
        assertTrue(good.quality.proc!!<worse.quality.proc!!);assertTrue(good.quality.total!!>worse.quality.total!!)
        assertEquals(listOf(2L,1L),out.matches.map { it.item.id })
        val limited=SellSearch.run(context(q.copy(maximumTotalQuality=50.0),ring(1,true),ring(2,false)),null)
        assertEquals(listOf(2L),limited.matches.map { it.item.id })
    }
    @Test fun primaryCutoffMigratesOnceAndSavedQueuesRequireTheirOrderToBeRefreshed() {
        val old=SellQuery(maximumProcQuality=25.0).toJson();old.remove("rollBasis")
        val migrated=SellQuery.of(old)
        assertNull(migrated.maximumProcQuality);assertEquals(25.0,migrated.maximumTotalQuality!!,0.0)
        assertEquals(migrated,SellQuery.of(migrated.toJson()))
        val extra=SellQuery.of(old.put("scoreFilter",true));assertEquals(25.0,extra.maximumProcQuality!!,0.0);assertNull(extra.maximumTotalQuality)
        val ctx=context(SellQuery(reforge=false),raw(1));val row=SellSearch.run(ctx,null).matches.single()
        val f=File.createTempFile("old-roll-order","json").also { it.delete() };val t=UpgradeTracker(f)
        val entries=listOf(row.item to UpgradeTracker.QueueEntry(1,null,"Speed",null,null,row.rolls))
        t.startQueue(entries,1,ctx.query)
        val saved=t.toJson();saved.remove("queueBasis");saved.getJSONObject("scoreQuery").remove("rollBasis")
        t.restore(saved);assertTrue(t.needsRollRefresh)
        assertTrue(UpgradeTracker(f).needsRollRefresh);assertEquals(1,t.queue.size)
        t.startQueue(entries,1,migrated);assertFalse(t.needsRollRefresh)
    }
    @Test fun inconsistentRawHistoryStaysUnknown() {
        val broken=raw(1);broken.getJSONArray("substats").getJSONObject(0).put("value",10)
        val out=SellSearch.run(context(SellQuery(reforge=false),broken),null)
        assertEquals(1,out.unknown);assertTrue(out.matches.isEmpty());assertNull(out.rows.single().rolls)
    }
    @Test fun lineForecastPreservesNoHitBranchAndRollMagnitudes() {
        val ctx=context(SellQuery(reforge=false),raw(1));val p=ctx.items.single()
        val line=RollReview.line(p,GearStat.SPD,false,true,true)
        assertEquals(11.0,line.current,0.0);assertEquals(11.0,line.minimum!!,0.0)
        assertEquals(15.0,line.maximum!!,0.0);assertEquals(16.0,line.rare!!,0.0);assertEquals(11.75,line.average!!,1e-12)
        for(ref in listOf(false,true)) {
            val l=RollReview.line(p,GearStat.SPD,ref,true,true)
            val outcomes=p.subs.flatMap { sub->Finish.range(sub.type,85).map { v->
                val count=if(sub.type==GearStat.SPD)p.subs.first().rolls+1 else p.subs.first().rolls
                val value=11.0+if(sub.type==GearStat.SPD)v else 0
                (value+if(ref)e7.gearscan.calc.Reforge.gain(GearStat.SPD,count) else 0.0) to (1.0/4/Finish.range(sub.type,85).count())
            } }
            assertEquals(outcomes.minOf { it.first },l.minimum!!,1e-12);assertEquals(outcomes.maxOf { it.first },l.maximum!!,1e-12)
            assertEquals(outcomes.sumOf { it.first*it.second },l.average!!,1e-10)
        }
    }
    @Test fun heroicMissingLineIncludesRevealFailureRatherThanConditioningOnSuccess() {
        val p=Item(1,Slot.HELMET,"SpeedSet","Heroic",85,9,GearStat.HP,2700.0,listOf(Substat(GearStat.SPD,12.0,4,false),Substat(GearStat.HP_P,4.0,1,false),Substat(GearStat.CR,3.0,1,false)),false,null)
        val line=RollReview.line(p,GearStat.CD,false,false,true)
        assertEquals(0.0,line.minimum!!,0.0);assertEquals(14.0,line.maximum!!,0.0)
        assertEquals(5.5*1.25/UpgradeRecord.allowedNew(p).size,line.average!!,1e-12)
    }
    @Test fun safeguardsAreAnyStatNotAnImpossibleJointTarget() {
        val ctx=context(SellQuery(reforge=false,keepStats=mapOf(GearStat.SPD to 13.0,GearStat.HP_P to 8.0)),raw(1))
        val out=SellSearch.run(ctx,null);val row=out.rows.single()
        assertTrue(row.protected);assertTrue(out.matches.isEmpty());assertTrue(row.safeguards.all { it.possible })
        assertFalse(GearPotential.forecast(row.item,GearPotential.Goal(mapOf(GearStat.SPD to 13.0,GearStat.HP_P to 8.0),false,false)).possible)
    }
    @Test fun rarePossibilitiesAndUnknownFutureCannotMasqueradeAsSafeZeros() {
        val rare=SellSearch.run(context(SellQuery(reforge=false,keepStats=mapOf(GearStat.SPD to 16.0),keepChance=0.0),raw(1)),null)
        assertTrue(rare.rows.single().protected);assertEquals(0.0,rare.rows.single().safeguards.single().chance,0.0)
        // the default 1% asks for ordinary odds: a rare-only reach does not hide the piece
        val ordinary=SellSearch.run(context(SellQuery(reforge=false,keepStats=mapOf(GearStat.SPD to 16.0)),raw(1)),null)
        assertFalse(ordinary.rows.single().protected)
        val unknown=SellSearch.run(context(SellQuery(reforge=false,keepStats=mapOf(GearStat.SPD to 20.0)),raw(1,family="event")),null)
        assertEquals(1,unknown.unknown);assertTrue(unknown.matches.isEmpty())
        val current=SellSearch.run(context(SellQuery(reforge=false,keepStats=mapOf(GearStat.SPD to 10.0)),raw(1,family="event")),null)
        assertTrue(current.rows.single().protected)
    }
    @Test fun primaryCriteriaAndSavedSettingsAreExplicitAndCheckpointed() {
        val q=SellQuery(reforge=false,maximumProcQuality=30.0,keepStats=mapOf(GearStat.RES to 20.0),keepChance=5.0)
        val f=File.createTempFile("roll-checkpoint","jsonl").also { it.deleteOnExit() }
        val a=SellSearch.run(context(q,raw(1,true),raw(2)),f)
        val b=SellSearch.run(context(q,raw(1,true),raw(2)),f)
        assertEquals(listOf(2L),a.matches.map { it.item.id });assertEquals(a.matches.single().rolls,b.matches.single().rolls)
        assertEquals(a.matches.single().lines,b.matches.single().lines);assertEquals(q,SellQuery.of(JSONObject(q.toJson().toString())))
        val legacy=q.toJson().put("sort","CHANCE");legacy.remove("scoreFilter")
        val migrated=SellQuery.of(legacy);assertFalse(migrated.scoreFilter);assertEquals(SellSort.QUALITY,migrated.sort);assertEquals(q.keepStats,migrated.keepStats)
    }
    @Test fun reviewQueueAppendsSavedProcsOnceAndRestoresWithoutBuildAdvice() {
        val ctx=context(SellQuery(reforge=false),raw(1));val row=SellSearch.run(ctx,null).matches.single()
        val file=File.createTempFile("roll-queue","json").also { it.delete() };val t=UpgradeTracker(file)
        t.startQueue(listOf(row.item to UpgradeTracker.QueueEntry(row.item.id,null,"Speed",null,null,row.rolls,1,true,true)),1,ctx.query.copy(keepStats=mapOf(GearStat.SPD to 13.0),keepChance=50.0))
        assertTrue(t.isRollReview);assertTrue(t.hasTarget);assertNull(t.goalOutcome);assertEquals(8,t.reviewRolls!!.size);assertFalse(t.reviewProtected)
        assertTrue(t.commit(2700.0,listOf(13.0,4.0,3.0,8.0),null,false))
        assertEquals(9,t.reviewRolls!!.size);assertTrue(t.reviewProtected);assertEquals(2.0,t.reviewRolls!!.last().value,0.0)
        val restored=UpgradeTracker(file);assertTrue(restored.isRollReview);assertEquals(t.reviewRolls,restored.reviewRolls)
        restored.undo();assertEquals(8,restored.reviewRolls!!.size)
        val repeated=UpgradeTracker.QueueEntry(row.item.id,null,"Speed",null,null,t.reviewRolls,t.record!!.history.size)
        t.startQueue(listOf(t.record!!.item to repeated),1,ctx.query);assertEquals(9,t.reviewRolls!!.size)
    }
    @Test fun heroicRevealAndCorrectionsAreNotCountedAsUpgradeProcs() {
        val initial=listOf(RollReview.Roll(GearStat.SPD,3.0,85))
        val p=Item(1,Slot.HELMET,"SpeedSet","Heroic",85,9,GearStat.HP,1944.0,listOf(Substat(GearStat.SPD,12.0,4,false),Substat(GearStat.CR,3.0,1,false),Substat(GearStat.RES,4.0,1,false)),false,null)
        val after=Item(1,p.slot,p.set,p.rank,85,12,p.mainType,p.mainValue,p.subs+Substat(GearStat.HP_P,4.0,1,false),false,null)
        val rolls=RollReview.append(initial,p,after)!!;assertTrue(rolls.last().reveal);assertEquals(0,RollReview.upgrades(rolls)!!.size)
        assertNull(RollReview.append(rolls,after,after))
    }
}
