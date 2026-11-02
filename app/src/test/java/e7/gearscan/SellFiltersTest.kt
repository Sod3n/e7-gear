package e7.gearscan

import e7.gearscan.data.*
import e7.gearscan.opt.*
import e7.gearscan.features.tracker.TrackerFeature
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

class SellFiltersTest {
    private fun raw(id:Long=1,enhance:Int=12,wearer:Long?=null,locked:Boolean=false):JSONObject {
        val events= mutableListOf<Pair<String,Double>>("speed" to 3.0,"max_hp_rate" to .05,"cri" to .04,"res" to .04)
        listOf("speed" to 3.0,"speed" to 3.0,"speed" to 4.0,"res" to .04,"speed" to 2.0).take(enhance/3).forEach { events+=it }
        val values=linkedMapOf<String,Double>();val counts=linkedMapOf<String,Int>()
        events.forEach { (s,v)->values[s]=(values[s]?:0.0)+v;counts[s]=(counts[s]?:0)+1 }
        val names=mapOf("speed" to GearStat.SPD,"max_hp_rate" to GearStat.HP_P,"cri" to GearStat.CR,"res" to GearStat.RES)
        return JSONObject().put("id",id).put("gear","Helmet").put("type","helm").put("set","SpeedSet").put("rank","Epic").put("level",85).put("enhance",enhance)
            .put("l",locked).put("code","ecw6h").put("mainStatId","cra6_helm_m").put("ingameEquippedId",wearer?.toString()?:"None")
            .put("main",JSONObject().put("type","Health").put("value",2700))
            .put("op",JSONArray().put(JSONArray().put("max_hp").put(540)).apply { events.forEach { (s,v)->put(JSONArray().put(s).put(v)) } })
            .put("substats",JSONArray(values.map { (s,v)->val t=names.getValue(s);JSONObject().put("type",t.key).put("value",if(t.showsPercent)kotlin.math.round(v*100) else v).put("rolls",counts.getValue(s)) }))
    }
    private fun item()=Box.parseItem(raw())!!
    private fun goal(floor:Double,reforge:Boolean=false,model:GearScore.Model=GearScore.Model(),budget:Int=5000)=GearScore.Goal(model,floor,reforge,true,budgetMs=budget)
    private fun box(vararg rows:JSONObject)=Box(JSONObject().put("items",JSONArray(rows.toList())).put("heroes",JSONArray()))
    private fun context(b:Box,q:SellQuery=SellQuery(maximumChance=100.0,reforge=false,scoreFilter=true,minimumProcs=0),records:Map<Long,UpgradeRecord> = emptyMap(),removed:Set<Long> = emptySet())=
        SellSearch.Context(b,records,99,removed,setOf(77),mapOf(77L to "Reference hero"),q,"test-content")

    private fun enumerate(p:Item,goal:GearScore.Goal):List<Pair<Double,Double>> {
        val out=ArrayList<Pair<Double,Double>>()
        fun walk(e:Int,subs:List<Substat>,prob:Double) {
            if(e>15) {
                val ref=goal.reforge&&goal.reforgeAllowed
                val ss=subs.map { Substat(it.type,it.value+if(ref)e7.gearscan.calc.Reforge.gain(it.type,it.rolls) else 0.0,it.rolls,false) }
                val main=if(ref)e7.gearscan.calc.Reforge.main(p.mainType,Finish.main15(p)) else Finish.main15(p)
                val finished=Item(p.id,p.slot,p.set,p.rank,if(ref)90 else p.level,15,p.mainType,main,ss,false,null)
                out+=goal.model.value(finished) to prob;return
            }
            val reveal=p.rank=="Heroic"&&e==12&&subs.size==3
            val types=if(reveal)GearStat.entries.filter { t->t!=p.mainType&&subs.none { it.type==t }&&!(p.slot==Slot.WEAPON&&t.stat==Stat.DEF)&&!(p.slot==Slot.ARMOR&&t.stat==Stat.ATK) } else subs.map { it.type }
            for(t in types)for(v in Finish.range(t,p.level)) {
                val changed=if(reveal)subs+Substat(t,v.toDouble(),1,false) else subs.map { if(it.type==t)Substat(t,it.value+v,it.rolls+1,false) else it }
                walk(e+3,changed,prob/types.size/Finish.range(t,p.level).count())
            }
        }
        walk((p.enhance/3+1)*3,p.subs,1.0);return out
    }
    @Test fun probabilityAndExpectedScoreMatchExhaustiveFinishes() {
        val epic=item();val heroic=Item(2,Slot.HELMET,"SpeedSet","Heroic",85,9,GearStat.HP,2700.0,
            listOf(Substat(GearStat.SPD,12.0,4,false),Substat(GearStat.HP_P,5.0,1,false),Substat(GearStat.CR,4.0,1,false)),false,null,main15=2700.0)
        for(p in listOf(epic,heroic))for(k in listOf(0,25,50,100))for(ref in listOf(false,true)) {
            val g=goal(100.0,ref,GearScore.Model(diversity=k));val exact=enumerate(p,g);val out=GearScore.forecast(p,g)
            assertTrue(out.supported);assertEquals(exact.filter { it.first>=100.0-1e-9 }.sumOf { it.second },out.chance,1e-10)
            assertEquals(exact.sumOf { it.first*it.second },out.expected!!,1e-8)
            assertEquals(exact.maxOf { it.first },out.best!!,1e-8)
        }
    }
    @Test fun equalityRareEndpointsAndZeroWeightsRemainHonest() {
        val p=item();assertEquals(1.0,GearScore.forecast(p,goal(103.4)).chance,1e-12)
        assertEquals(49.0/60,GearScore.forecast(p,goal(103.400001)).chance,1e-10)
        val rare=GearScore.forecast(p,goal(108.4));assertTrue(rare.rareOnly);assertEquals(0.0,rare.chance,0.0)
        val zero=GearScore.Model(GearStat.entries.associateWith { 0.0 },0,100)
        assertEquals(1.0,GearScore.forecast(p,goal(0.0,model=zero)).chance,0.0)
        assertEquals(0.0,GearScore.forecast(p,goal(1.0,model=zero)).chance,0.0)
    }
    @Test fun timeoutAndCancellationCannotBecomeZeroChanceAdvice() {
        val p=item();val out=GearScore.forecast(p,goal(100.0,budget=0))
        assertFalse(out.supported);assertTrue(out.reason!!.contains("time limit"))
        assertFalse(SellSearch.accepts(SellSearch.Row(p,goal(100.0),out,SellSearch.Quality(null,null,0),0),SellQuery()))
        try { GearScore.forecast(p,goal(101.23)) { true };fail("Cancellation was ignored") }catch(_:CancellationException) {}
    }
    @Test fun visibleBreakdownGroupsRelatedStatsAndKeepsMainSeparate() {
        val p=Item(100,Slot.NECKLACE,"SpeedSet","Epic",90,15,GearStat.ATK_P,65.0,listOf(
            Substat(GearStat.HP,100.0,1,false),Substat(GearStat.HP_P,5.0,1,false),
            Substat(GearStat.SPD,10.0,3,false),Substat(GearStat.CR,5.0,1,false)),false,null)
        val b=GearScore.Model(diversity=50).breakdown(p)
        assertEquals(65.0,b.main,0.0);assertEquals(35.0,b.substats,1e-9)
        assertEquals(7.0,b.groups.getValue(Stat.HP),1e-9)
        assertEquals(7.5,b.discount,1e-9);assertEquals(92.5,b.total,1e-9)
        assertEquals(b.main+b.substats-b.discount,b.total,1e-9)
        assertEquals(85.0,GearScore.Model(diversity=100).value(p),1e-9)
    }
    @Test fun allEditableSettingsRoundTripWithoutAKeyboardOrHiddenDefaults() {
        val q=SellQuery(score=GearScore.Model(GearScore.weights+(GearStat.ATK to .1234),175,37),maximumChance=12.5,reforge=false,
            reference=SellReference.SELECTED,referenceHeroes=setOf(77,88),referenceMinimum=15,matchMain=true,floorPercent=125.0,floors=mapOf(Slot.HELMET to 123.4567),
            slots=setOf(Slot.HELMET),sets=setOf("SpeedSet"),mains=setOf(GearStat.HP),minimumEnhance=6,maximumEnhance=15,
            bagOnly=false,includeLocked=true,protectPool=false,includeRare=true,includeUnknown=true,standardOnly=false,maximumCurrentScore=100.01,
            maximumProcQuality=25.0,maximumTotalQuality=50.0,minimumProcs=3,forecastMs=1500,sort=SellSort.SCORE,descending=true,scoreFilter=true,keepStats=mapOf(GearStat.SPD to 20.0),keepChance=5.0)
        assertEquals(q,SellQuery.of(JSONObject(q.toJson().toString())))
        assertNotNull(q.copy(maximumChance=Double.NaN).error());assertNotNull(q.copy(minimumEnhance=15,maximumEnhance=6).error())
    }
    @Test fun referenceFloorsUseTheSameModelAndObserveAllRemovals() {
        val b=box(raw(1),raw(2,15,77));val p=b.itemsById.getValue(1)
        val first=context(b);assertEquals(first.query.score.value(b.itemsById.getValue(2)),first.floor(p)!!.value,1e-9)
        val model=GearScore.Model(diversity=50);val changed=context(b,first.query.copy(score=model))
        assertEquals(model.value(b.itemsById.getValue(2)),changed.floor(p)!!.value,1e-9)
        val missing=context(b,removed=setOf(2));assertNull(missing.floor(p));assertTrue(missing.references.isEmpty())
        val custom=context(b,first.query.copy(floors=mapOf(Slot.HELMET to 80.0),floorPercent=125.0),removed=setOf(2))
        assertEquals(100.0,custom.floor(p)!!.value,0.0)
    }
    @Test fun recordedUpgradesAndFreshOwnershipBothWinOverOldMetadata() {
        val b=box(raw(1),raw(2,15,77));val original=b.itemsById.getValue(1)
        val history=UpgradeRecord(99,listOf(UpgradeRecord.fromScan(original))).record(15,2700.0,listOf(17.0,5.0,4.0,8.0))
        b.setLocked(original,true)
        val ctx=context(b,records=mapOf(1L to history));val observed=ctx.items.first { it.id==1L }
        assertEquals(15,observed.enhance);assertEquals(17.0,observed.subs[0].value,0.0);assertTrue(observed.locked)
        assertNotEquals(context(b).signature(),ctx.signature());assertEquals(5,ctx.quality(observed).count)
    }
    @Test fun checkpointResumeAndMissingFloorDoNotProduceStaleActions() {
        val b=box(raw(1),raw(3,9),raw(2,15,77));val ctx=context(b);val f=File.createTempFile("sell-progress",".jsonl").also { it.deleteOnExit() }
        var stop=false
        try { SellSearch.run(ctx,f,{stop}) { done,_->if(done==1)stop=true };fail("Must stop") }catch(_:CancellationException) {}
        val done=SellSearch.run(ctx,f);assertEquals(2,done.rows.size);assertEquals(3,f.readLines().size)
        val again=SellSearch.run(ctx,f);assertEquals(done.matches.map { it.item.id },again.matches.map { it.item.id });assertEquals(3,f.readLines().size)
        val missing=SellSearch.run(context(b,removed=setOf(2)),f)
        assertEquals(2,missing.unknown);assertTrue(missing.matches.isEmpty());assertNotEquals(done.signature,missing.signature)
    }
    @Test fun lowProcMagnitudeKeepsStartingAndRevealValuesSeparate() {
        val b=box(raw(1));val q=context(b).quality(b.items.single())
        assertEquals(4,q.count);assertEquals((.5+.5+1.0+0.0)/4*100,q.proc!!,1e-9)
    }
    @Test fun scoreQueueUpdatesAfterSavingProcsAndSurvivesRestore() {
        val p=item();val env=TrackerEnv(kotlin.io.path.createTempDirectory("score-queue").toFile());val t=env.t
        val q=SellQuery(reforge=false,maximumChance=100.0);val entry=TrackerFeature.QueueEntry(p.id,null,"Speed",null,goal(106.0))
        t.startQueue(listOf(p to entry),99,q);val before=t.readout.goalOutcome!!.chance
        assertTrue(before in 0.0..1.0);assertTrue(before<1)
        assertTrue(t.commit(2700.0,listOf(17.0,5.0,4.0,8.0),null,false));assertEquals(1.0,t.readout.goalOutcome!!.chance,0.0)
        val restored=env.reopen().t;assertEquals(q,restored.scoreQuery);assertEquals(t.scoreGoal,restored.scoreGoal);assertEquals(t.queue,restored.queue)
        assertEquals("Comparison reached",restored.readout.title);restored.undo();assertEquals(before,restored.readout.goalOutcome!!.chance,1e-12)
    }
    @Test fun realReviewPiecesMatchTheIndependentExactPythonReference() {
        val dir=System.getenv("E7_SELL_EVIDENCE");assumeTrue(dir!=null)
        val root=File(dir!!);val backup=JSONObject(File("/Users/a1/Documents/epic7/gear_cleanup/fixtures/backup.json").readText());val b=Box(backup.getJSONObject("box"));val state=backup.getJSONObject("state")
        val tiers=state.getJSONObject("tiers");val pool=b.allHeroes.filter { tiers.optInt(it.id.toString(),1)>=2 }.map { it.id }.toSet()
        val summary=JSONObject(File(root,"probability_summary.json").readText());val manifest=JSONObject(File(root,"probability_manifest.json").readText())
        val evidence=File(manifest.getString("cache")).readLines().map(::JSONObject).associateBy { it.getLong("id") }
        val ids=JSONObject(File(root.parentFile,"low_procs/summary.json").readText()).getJSONObject("first_pass_screen").getJSONArray("ids").let { a->(0 until a.length()).map(a::getLong) }
        for((label,k) in listOf("total" to 0,"focus25" to 25,"focus50" to 50,"focus100" to 100))for(ref in listOf(false,true)) {
            val q=SellQuery(score=GearScore.Model(diversity=k),reforge=ref,standardOnly=false,forecastMs=5000)
            val ctx=SellSearch.Context(b,emptyMap(),99,emptySet(),pool,b.allHeroes.associate { it.id to it.name },q)
            for(id in ids) {
                val p=b.itemsById.getValue(id);val goal=ctx.goal(p);val out=GearScore.forecast(p,goal)
                val wanted=evidence.getValue(id).getJSONObject("chance").getJSONObject(if(ref)"with_reforge" else "unreforged").getJSONObject(label+"_as_worn").getDouble("chance")
                assertTrue("$id: ${out.reason}",out.supported);assertEquals("$id $label ref=$ref",wanted,out.chance,1e-9)
                assertEquals(summary.getJSONObject("reference_floor_scores").getJSONObject(p.slot.key).getJSONObject(label+"_as_worn").getDouble("points"),goal.floor!!,1e-8)
            }
        }
    }
    @Test fun defaultSafeguardsSkipFlatStatsAndOldFlatDefaultsMigrate() {
        assertEquals(setOf(GearStat.SPD,GearStat.CR,GearStat.CD,GearStat.ATK_P,GearStat.HP_P,GearStat.DEF_P,GearStat.EFF,GearStat.RES),SellQuery.DEFAULT_GUARDS.keys)
        assertEquals(20.0,SellQuery.DEFAULT_GUARDS.getValue(GearStat.SPD),0.0)
        val v1=SellQuery().toJson().put("guardsV",1).put("keepStats",JSONObject().put(GearStat.SPD.key,20.0).put(GearStat.ATK.key,230.0).put(GearStat.HP.key,1500.0))
        val back=SellQuery.of(v1).keepStats
        assertFalse(GearStat.ATK in back);assertEquals(1500.0,back.getValue(GearStat.HP),0.0);assertEquals(20.0,back.getValue(GearStat.SPD),0.0)
    }
}
