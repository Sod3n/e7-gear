package e7.gearscan.opt

import e7.gearscan.data.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Pure, restartable score-filter search. It never marks a piece sold or modifies the scan. */
object SellSearch {
    data class Floor(val value:Double,val item:Item?,val owner:String,val manual:Boolean) {
        fun label()=if(manual)"Custom floor" else "$owner · +${item?.enhance}"
    }
    data class Quality(val proc:Double?,val total:Double?,val count:Int) {
        fun toJson()=JSONObject().put("proc",proc?:JSONObject.NULL).put("total",total?:JSONObject.NULL).put("count",count)
        companion object { fun of(j:JSONObject)=Quality(if(j.isNull("proc"))null else j.getDouble("proc"),if(j.isNull("total"))null else j.getDouble("total"),j.getInt("count")) }
    }
    data class Row(val item:Item,val goal:GearScore.Goal,val forecast:GearScore.Forecast,val quality:Quality,val gameScore:Int,val qualityUnknown:Boolean=false,
        val scoreUsed:Boolean=true,val rolls:List<RollReview.Roll>?=null,val lines:List<RollReview.Line> = emptyList(),val safeguards:List<RollReview.Safeguard> = emptyList(),val keepChance:Double=0.0,val es:EsForecast.Es?=null) {
        val protected get()=safeguards.any { it.active(keepChance) }
        val unknown get()=scoreUsed&&!forecast.supported||qualityUnknown||!protected&&safeguards.any { !it.known }
        fun toJson()=JSONObject().put("id",item.id).put("goal",goal.toJson()).put("forecast",forecast.toJson()).put("quality",quality.toJson()).put("gameScore",gameScore).put("qualityUnknown",qualityUnknown)
            .put("scoreUsed",scoreUsed).put("rolls",RollReview.rollsJson(rolls)).put("lines",org.json.JSONArray(lines.map { it.toJson() })).put("safeguards",org.json.JSONArray(safeguards.map { it.toJson() })).put("keepChance",keepChance).put("es",es?.toJson()?:JSONObject.NULL)
    }
    class Context(val box:Box,val records:Map<Long,UpgradeRecord>,val scan:Long,val removed:Set<Long>,val pool:Set<Long>,val names:Map<Long,String>,val query:SellQuery,val contentVersion:String="") {
        private val original=box.itemsById
        val items=box.items.filter { it.id !in removed }.map { original->
            val observed=records[original.id]?.takeIf { it.scan==scan }?.item
            if(observed==null)original else Item(original.id,original.slot,original.set,observed.rank,observed.level,observed.enhance,observed.mainType,observed.mainValue,observed.subs,original.locked,original.equippedOn,main15=observed.main15)
        }
        /** Median game ES of your +15 pieces per slot, the default bar a piece should reach. */
        val autoEsBars:Map<Slot,Int> by lazy { items.filter { it.enhance==15&&it.level>=85 }.groupBy { it.slot }.mapValues { (_,v)->v.map { gameScore(it) }.sorted()[v.size/2] } }
        fun esBar(slot:Slot)=query.esBars[slot]?:autoEsBars[slot]
        fun es(item:Item)=EsForecast.of(item,gameScore(item),esBar(item.slot),query.reforge,box.canReforge(item.id),coverage(item),query.forecastMs)
        val inventoryRanks by lazy { InventoryOrder.index(box,records.filterValues { it.scan==scan&&it.history.size>1 }.mapValues { it.value.item },removed) }
        val raw=box.json.getJSONArray("items").let { a->(0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.optLong("id",it.optLong("ingameId")) } }
        private val duplicateHistories=raw.values.filter { it.optInt("enhance")>0 }.groupingBy { historyKey(it) }.eachCount()
        val references=items.filter { it.equippedOn!=null && it.enhance>=query.referenceMinimum && when(query.reference) {
            SellReference.POOL->it.equippedOn in pool;SellReference.EQUIPPED->true;SellReference.SELECTED->it.equippedOn in query.referenceHeroes } }
        fun floor(item:Item):Floor? {
            query.floors[item.slot]?.let { return Floor(it*query.floorPercent/100,null,"Custom floor",true) }
            val ref=references.filter { it.slot==item.slot && (!query.matchMain||it.mainType==item.mainType) }.minByOrNull(query.score::value)?:return null
            return Floor(query.score.value(ref)*query.floorPercent/100,ref,names[ref.equippedOn]?:"Hero not in scan",false)
        }
        fun goal(item:Item):GearScore.Goal {
            val f=floor(item)
            return GearScore.Goal(query.score,f?.value,query.reforge,box.canReforge(item.id),coverage(item),f?.label()?:"No reference floor",query.forecastMs,f?.item?.id)
        }
        fun coverage(item:Item):Boolean {
            if(item.enhance==15)return true
            val o=raw[item.id]?:return false
            if(item.level !in listOf(85,88) || item.subs.any { it.modified } || !item.main15.isFinite())return false
            if(o.optBoolean("preset",false)||o.optString("unique_type")=="y"||o.optString("mainStatId").startsWith("un"))return false
            if((duplicateHistories[historyKey(o)]?:0)>1)return false
            if(!query.standardOnly)return true
            if(quality(item).total==null)return false
            val family=o.optString("mainStatId").substringBefore('_')
            return item.level==85 && family in setOf("cra6","chaos","esp6","edw6","al85")
        }
        fun rolls(item:Item):List<RollReview.Roll>? {
            val o=raw[item.id]?:return null
            if(item.level !in listOf(85,88,90)||item.subs.any { it.modified })return null
            val events=rawEvents(o)?.toMutableList()?:return null
            val rec=records[item.id]?.takeIf { it.scan==scan }
            if(rec!=null)for((before,after) in rec.history.zipWithNext()) {
                if(after.enhance==before.enhance)return null
                val added=after.subs.firstOrNull { a->before.subs.none { it.type==a.type } }
                if(added!=null)events+=added.type to added.value else {
                    val changed=after.subs.mapNotNull { a->before.subs.firstOrNull { it.type==a.type }?.let { b->(a.value-b.value).takeIf { it>0 }?.let { a.type to it } } }
                    if(changed.size!=1)return null
                    events+=changed.single()
                }
            }
            val first=if(item.rank=="Epic")4 else if(item.rank=="Heroic")3 else return null
            if(events.size!=first+item.enhance/3)return null
            val level=if(item.level==90)85 else item.level
            if(events.any { (s,v)->v<Finish.range(s,level).first || v>Finish.maximumRoll(s,level) })return null
            val grouped=events.groupBy { it.first }
            if(grouped.keys!=item.subs.map { it.type }.toSet())return null
            if(item.subs.any { sub->val e=grouped.getValue(sub.type);e.size!=sub.rolls||kotlin.math.abs(e.sumOf { it.second }+(if(item.level==90)e7.gearscan.calc.Reforge.gain(sub.type,sub.rolls) else 0.0)-sub.value)>1e-6 })return null
            return events.mapIndexed { i,(stat,value)->val e=if(i<first)0 else (i-first+1)*3;RollReview.Roll(stat,value,level,e,item.rank=="Heroic"&&e==12) }
        }
        fun quality(item:Item):Quality {
            val events=rolls(item)?:return Quality(null,null,0)
            val procs=events.filter { it.upgrade }
            return Quality(procs.takeIf { it.isNotEmpty() }?.map { it.fraction*100 }?.average(),events.map { it.fraction*100 }.average(),procs.size)
        }
        fun signature():String {
            val text=RollReview.VERSION+GearScore.VERSION+EsForecast.VERSION+autoEsBars.toSortedMap()+query.copy(rollOn=0.0).toJson()+box.toText()+records.values.filter { it.scan==scan }.sortedBy { it.item.id }.joinToString { it.toJson().toString() }+
                removed.filter { original[it]?.equippedOn!=null }.sorted()+pool.sorted()+names.toSortedMap()+scan+contentVersion
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(java.util.Locale.US,it) }
        }
        fun gameScore(item:Item)=InventoryOrder.score(item,if((records[item.id]?.takeIf { it.scan==scan }?.history?.size?:0) > 1)null else raw[item.id])
        private fun historyKey(o:JSONObject)=o.optJSONArray("op")?.toString().orEmpty()
        private fun rawEvents(o:JSONObject):List<Pair<GearStat,Double>>? {
            val op=o.optJSONArray("op")?:return null
            val keys=mapOf("att" to GearStat.ATK,"att_rate" to GearStat.ATK_P,"max_hp" to GearStat.HP,"max_hp_rate" to GearStat.HP_P,"def" to GearStat.DEF,"def_rate" to GearStat.DEF_P,"speed" to GearStat.SPD,"cri" to GearStat.CR,"cri_dmg" to GearStat.CD,"acc" to GearStat.EFF,"res" to GearStat.RES)
            return (1 until op.length()).mapNotNull { i->op.getJSONArray(i).takeIf { it.length()==2 }?.let { e->keys[e.getString(0)]?.let { t->t to if(t.showsPercent) kotlin.math.round(e.getDouble(1)*100) else e.getDouble(1) } } }
        }
    }
    data class Result(val signature:String,val query:SellQuery,val rows:List<Row>,val matches:List<Row>,val unknown:Int,val seconds:Double,val references:Int)
    fun accepts(row:Row,q:SellQuery):Boolean {
        if(row.protected)return false
        if(row.unknown&&!q.includeUnknown)return false
        if(!q.scoreFilter)return true
        if(!row.forecast.supported)return q.includeUnknown
        if(row.forecast.rareOnly&&!q.includeRare)return false
        return row.forecast.chance*100<=q.maximumChance+1e-9
    }
    fun run(context:Context,file:File?,cancelled:()->Boolean={false},progress:(Int,Int)->Unit={_,_->}):Result {
        val started=System.nanoTime();val q=context.query;require(q.error()==null)
        val signature=context.signature();val saved=LinkedHashMap<Long,JSONObject>()
        if(file?.exists()==true) {
            val lines=file.readLines();val header=lines.firstOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() }
            if(header?.optString("signature")==signature)for(line in lines.drop(1)) { val row=runCatching { JSONObject(line) }.getOrNull()?:break;saved[row.getLong("id")]=row }
        }
        file?.let { f->f.parentFile?.mkdirs();val tmp=File(f.path+".tmp");tmp.writeText(JSONObject().put("signature",signature).toString()+"\n"+saved.values.joinToString("") { it.toString()+"\n" });check(tmp.renameTo(f)) }
        val candidates=context.items.filter { q.includes(it,context.pool) };val rows=ArrayList<Row>();var done=0
        progress(done,candidates.size)
        for(item in candidates) {
            if(cancelled())throw CancellationException()
            val quality=context.quality(item)
            if(RollReview.expectedProcs(item)<q.minimumProcs) { progress(++done,candidates.size);continue }
            if((q.maximumProcQuality!=null&&quality.proc?.let { quality.count<q.minimumProcs || it>q.maximumProcQuality+1e-8 }==true)||
                (q.maximumTotalQuality!=null&&quality.total?.let { it>q.maximumTotalQuality+1e-8 }==true)) { progress(++done,candidates.size);continue }
            val qualityUnknown=(!q.scoreFilter&&quality.total==null)||q.maximumProcQuality!=null&&quality.proc==null || q.maximumTotalQuality!=null&&quality.total==null
            val row=saved[item.id]?.takeIf { !it.getJSONObject("forecast").optString("reason").startsWith("Forecast time limit") }?.let { j->runCatching {
                val la=j.getJSONArray("lines");val sa=j.getJSONArray("safeguards")
                Row(item,GearScore.Goal.of(j.getJSONObject("goal")),GearScore.Forecast.of(j.getJSONObject("forecast")),Quality.of(j.getJSONObject("quality")),j.getInt("gameScore"),j.optBoolean("qualityUnknown",false),j.getBoolean("scoreUsed"),RollReview.rollsOf(j.optJSONArray("rolls")),(0 until la.length()).map { RollReview.Line.of(la.getJSONObject(it)) },(0 until sa.length()).map { RollReview.Safeguard.of(sa.getJSONObject(it)) },j.getDouble("keepChance"),j.optJSONObject("es")?.let(EsForecast.Es::of))
            }.getOrNull() }?:run {
                val goal=context.goal(item);val cover=context.coverage(item);val allowed=context.box.canReforge(item.id)
                val forecast=if(q.scoreFilter)GearScore.forecast(item,goal,cancelled) else GearScore.Forecast(0.0,false,false,q.score.value(item),null,null,null,"Combined comparison is off")
                val lines=item.subs.map { RollReview.line(item,it.type,q.reforge,allowed,cover) }
                val guards=q.keepStats.map { (stat,minimum)->RollReview.safeguard(item,stat,minimum,q.reforge,allowed,cover) }
                Row(item,goal,forecast,quality,context.gameScore(item),qualityUnknown,q.scoreFilter,context.rolls(item),lines,guards,q.keepChance,context.es(item)).also { row->
                    if(cancelled())throw CancellationException();file?.appendText(row.toJson().toString()+"\n") }
            }
            rows+=row;progress(++done,candidates.size)
        }
        var matches=rows.filter { accepts(it,q) }.sortedWith(when(if(!q.scoreFilter&&q.sort in listOf(SellSort.SCORE,SellSort.CHANCE))SellSort.QUALITY else q.sort) {
            SellSort.QUALITY->compareBy<Row> { it.quality.total?:Double.POSITIVE_INFINITY }.thenByDescending { it.quality.count }.thenBy { it.item.id }
            SellSort.CHANCE->compareBy<Row> { if(it.forecast.supported)it.forecast.chance else Double.POSITIVE_INFINITY }.thenBy { it.forecast.current }.thenBy { it.item.id }
            SellSort.SCORE->compareBy<Row> { it.forecast.current }.thenBy { it.item.id }
            SellSort.INVENTORY->compareBy<Row> { context.inventoryRanks[it.item.id]?.number?:Int.MAX_VALUE }.thenBy { it.item.id }
        })
        if(q.descending)matches=matches.asReversed()
        if(q.sort==SellSort.QUALITY||!q.scoreFilter&&q.sort in listOf(SellSort.SCORE,SellSort.CHANCE))matches=matches.sortedBy { it.quality.total==null }
        return Result(signature,q,rows,matches,rows.count { it.unknown },(System.nanoTime()-started)/1e9,context.references.size)
    }
}
