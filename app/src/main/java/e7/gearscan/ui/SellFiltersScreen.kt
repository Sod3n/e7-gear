package e7.gearscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.*
import e7.gearscan.data.*
import e7.gearscan.opt.*

private fun openSell(m:AppModel,k:SheetKind,id:Long=0) { m.sheet=Sheet(k,id) }
private fun scoreText(v:Double)=String.format(java.util.Locale.US,"%.4f",v).trimEnd('0').trimEnd('.')
private fun rollLimitText(q:SellQuery)=when(q.maximumTotalQuality) {
    null->"All overall roll values";0.0->"Only minimum rolls";25.0->"Average in bottom quarter";50.0->"Average at/below midrange";100.0->"Average up to usual maximum";else->"Average within lower ${fmtNum(q.maximumTotalQuality)}% of range"
}
private fun chanceText(r:SellSearch.Row)=if(!r.forecast.supported)"Unknown" else if(r.forecast.rareOnly)"Rare only" else GearPotential.percent(r.forecast.chance)

private fun referenceText(goal:GearScore.Goal)=goal.referenceLabel()

@Composable
private fun PointsRow(label:String,value:Double,strong:Boolean=false) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(label,style=zen(12.sp,color=if(strong)C.ink else C.muted),modifier=Modifier.weight(1f))
        Text("${fmtNum(value)} pts",style=if(strong)dela(15.sp,C.blue) else zen(12.sp))
    }
}

@Composable
private fun ScoreBreakdown(item:Item,model:GearScore.Model,details:Boolean=false) {
    val b=model.breakdown(item)
    PointsRow("Main stat",b.main)
    PointsRow("All substats",b.substats)
    PointsRow("Diversity discount (${model.diversity}%)",-b.discount)
    PointsRow("Total points",b.total,true)
    if(details) {
        VSpace(5.dp)
        val current=if(item.enhance<15)UpgradeRecord.fromScan(item) else item
        Text("Each value × its point weight",style=zen(11.sp,color=C.muted))
        Text("Main ${current.mainType.short}: ${current.mainType.format(current.mainValue)} × ${scoreText(model.weight(current.mainType))}${if(model.mainPercent!=100)" × ${model.mainPercent}%" else ""}",style=zen(11.sp))
        current.subs.forEach { sub->Text("${sub.type.short}: ${sub.type.format(sub.value)} × ${scoreText(model.weight(sub.type))} = ${fmtNum(sub.value*model.weight(sub.type))}",style=zen(11.sp)) }
        val largest=b.groups.maxByOrNull { it.value }
        if(largest!=null)Text("Strongest group: ${largest.key.label} · ${fmtNum(largest.value)} pts. The discount applies to the other ${fmtNum(b.substats-largest.value)} substat points.",style=zen(11.sp,color=C.muted))
    }
}

@Composable
private fun ComparisonOwner(model:AppModel,item:Item) {
    val hero=item.equippedOn?.let(model::hero)
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        if(hero!=null)Face(model.face(hero),26.dp)
        Column(Modifier.weight(1f)) {
            Text(hero?.let(model::heroName)?:"Equipped comparison piece",style=zen(12.sp))
            Text("+${item.enhance} · ${model.content.set(item.set)?.name?:item.set.removeSuffix("Set")} ${item.slot.label}",style=zen(10.sp,color=C.muted))
        }
    }
}

@Composable
private fun BenchmarkCard(model:AppModel,slot:Slot) {
    val q=model.sellQuery;val auto=model.sellFloorPreview(slot);val custom=q.floors[slot]
    val points=(custom?:auto?.value)?.times(q.floorPercent/100)
    Card(shadow=null,padding=PaddingValues(12.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(slot.label,style=zen(14.sp),modifier=Modifier.weight(1f))
            Text(points?.let { "${fmtNum(it)} pts" }?:"—",style=dela(15.sp,C.blue))
        }
        VSpace(7.dp)
        when {
            custom!=null->Text("Your custom score",style=zen(12.sp,color=C.muted))
            auto?.item!=null->{
                ComparisonOwner(model,auto.item)
                VSpace(5.dp)
                val b=q.score.breakdown(auto.item)
                Text("Main ${fmtNum(b.main)} + subs ${fmtNum(b.substats)}${if(b.discount>0)" − discount ${fmtNum(b.discount)}" else ""}",style=zen(11.sp,color=C.muted))
            }
            q.matchMain->Text("A separate comparison for each main stat. See the matching piece in results.",style=zen(11.sp,color=C.muted))
            else->Text("No equipped piece in this slot. Choose more heroes or enter a custom score below.",style=zen(11.sp,color=C.deepPink))
        }
        if(q.floorPercent!=100.0&&points!=null)Text("${fmtNum(custom?:auto!!.value)} × ${fmtNum(q.floorPercent)}% comparison scale",style=zen(10.sp,color=C.muted))
    }
}

@Composable
private fun FinishComparison(row:SellSearch.Row) {
    val values=listOfNotNull("Now" to row.forecast.current,row.forecast.expected?.let { "Average finish" to it },row.goal.floor?.let { "Comparison" to it })
    val maximum=values.maxOf { it.second }.coerceAtLeast(1.0)
    Column(verticalArrangement=Arrangement.spacedBy(9.dp)) {
        values.forEach { (name,value)->
            Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                PointsRow(name,value,name=="Comparison")
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.line)) {
                    Box(Modifier.fillMaxWidth((value/maximum).toFloat().coerceIn(0f,1f)).fillMaxHeight().background(if(name=="Comparison")C.pink else C.blue))
                }
            }
        }
    }
}

@Composable
private fun ScoringControls(model:AppModel) {
    val q=model.sellQuery
    val example=remember(model.lastScan) { model.sellFloorPreview(Slot.HELMET)?.item?:model.box?.items?.firstOrNull() }
    Text("Main stat + substats = points. Higher means more weighted stats; it does not measure a complete hero build.",style=zen(12.sp))
    SellNumber("Diversity penalty %",q.score.diversity.toDouble(),0.0,100.0,0) { model.updateSellQuery(q.copy(score=q.score.copy(diversity=(it?:0.0).toInt()))) }
    CardGrid(listOf(0,25,50,100),columns=4) { n,m->SellChoice("$n%",m,q.score.diversity==n) { model.updateSellQuery(q.copy(score=q.score.copy(diversity=n))) } }
    Text("0%: every substat counts in full. 100%: only the strongest substat group counts. The main stat is separate.",style=zen(11.sp,color=C.muted))
    if(example!=null)Card(shadow=null,padding=PaddingValues(12.dp)) {
        Text("See it on your gear",style=dela(15.sp))
        VSpace(8.dp);ComparisonOwner(model,example);VSpace(8.dp)
        var calculation by remember { mutableStateOf(false) }
        ScoreBreakdown(example,q.score,calculation)
        Text("The comparison gear is rescored with these same settings.",style=zen(10.sp,color=C.muted))
        VSpace(6.dp);LinkText(if(calculation)"Hide point calculation" else "Show point calculation") { calculation=!calculation }
    }
    LinkText("Edit stat weights & main") { openSell(model,SheetKind.SELL_WEIGHTS) }
    LinkText("Reset score model") { model.updateSellQuery(q.copy(score=GearScore.Model())) }
}

internal fun esVerdict(es:EsForecast.Es,rollOn:Double):Pair<String,Boolean>? = when {
    !es.supported||es.bar==null->null
    es.reached->"At your ES bar ${es.bar}" to true
    es.finishShare==0.0->"Finished below your bar ${es.bar}" to false
    es.worthRolling(rollOn)->"Worth rolling · ${GearPotential.percent(es.chance)} to reach ${es.bar}" to true
    es.possible&&es.chance==0.0->"Only rare rolls reach ${es.bar}" to false
    else->"Below your ${fmtNum(rollOn)}% · ${GearPotential.percent(es.chance)} to reach ${es.bar}" to false
}

@Composable
internal fun EsSummary(es:EsForecast.Es?,rollOn:Double) {
    if(es==null) { Text("Equipment Score not checked · update the list",style=zen(11.sp,color=C.muted));return }
    Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("ES ${es.now}",style=dela(17.sp))
        if(es.finishShare>0)Text(es.typical?.let { "now → usually ${it.toInt()} at +15${es.best?.let { b->" · up to ${b.toInt()}" }?:""}" }?:"now · finish not forecast",style=zen(11.sp,color=C.muted))
    }
    esVerdict(es,rollOn)?.let { (text,good)->Text(text,style=zen(12.sp,color=if(good)C.blue else C.deepPink)) }
        ?:Text(if(es.bar==null)"No +15 piece in this slot to set a bar · set one in Worth rolling" else "Chance not forecast for this piece",style=zen(11.sp,color=C.muted))
    if(es.finishShare>0)Text("Finishing costs ${(es.finishShare*100).toInt()}% of a full +0 → +15",style=zen(10.sp,color=C.muted))
}

@Composable
private fun RollTable(rolls:List<RollReview.Roll>?) {
    if(rolls==null) { Text("No reliable roll history for this piece.",style=zen(11.sp,color=C.muted));return }
    Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
        rolls.forEach { roll->
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(if(roll.enhance==0)"Start" else "+${roll.enhance}${if(roll.reveal)" new" else ""}",style=zen(10.sp,color=C.muted),modifier=Modifier.width(48.dp))
                StatIcon(roll.stat.stat,12.dp,C.ink)
                Text("${roll.stat.short} +${roll.stat.format(roll.value)}",style=zen(12.sp),modifier=Modifier.weight(1f))
                val f=roll.fraction.toFloat().coerceIn(0f,1f);val low=roll.fraction<.5
                Box(Modifier.width(72.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.line)) {
                    Box(Modifier.fillMaxWidth(maxOf(f,.06f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(if(low)C.deepPink else C.blue))
                }
            }
        }
    }
}

@Composable
private fun SellCardHead(model:AppModel,row:SellSearch.Row) {
    val item=if(row.item.enhance<15)UpgradeRecord.fromScan(row.item) else row.item
    Row(verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top=4.dp)) { GearTile(item,46.dp,setIcon=model.content.set(item.set)?.icon) }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("${model.content.set(item.set)?.name?:item.set.removeSuffix("Set")} ${item.slot.label}",style=zen(13.sp),modifier=Modifier.weight(1f),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                row.rolls?.let { Text("${RollReview.overallWeakCount(it)}/${it.size} low rolls",style=zen(10.sp,color=C.muted)) }
            }
            MainStatLine(item)
            GearSearchSubs(item)
        }
    }
}

@Composable
private fun OverallRollSummary(rolls:List<RollReview.Roll>?) {
    if(rolls==null)Text("Roll history unavailable",style=zen(12.sp,color=C.muted)) else {
        Text("${RollReview.overallWeakCount(rolls)} of ${rolls.size} rolls below midrange",style=zen(12.sp,color=C.blue))
        val starts=rolls.count { it.enhance==0 };val upgrades=rolls.count { it.upgrade };val reveals=rolls.count { it.reveal }
        Text("$starts starting + $upgrades upgrade${if(reveals>0)" + $reveals new-line" else ""} rolls",style=zen(11.sp,color=C.muted))
        RollReview.focus(rolls)?.let { focus->Text("Most upgrade hits: ${focus.key.short} · ${focus.value} of $upgrades",style=zen(11.sp)) }
    }
}

@Composable
private fun StatFinish(line:RollReview.Line,reforge:Boolean,compact:Boolean=false) {
    val s=line.stat
    val label="${s.short} ${s.format(line.current)}"
    if(line.maximum==null)Text("$label · finish not forecast",style=zen(11.sp,color=C.muted))
    else Text(if(compact)"$label → up to ${s.format(line.maximum)}" else "$label now · ${s.format(line.minimum!!)}–${s.format(line.maximum)} at +15${if(reforge)" & eligible reforge" else ""}",style=zen(11.sp,color=C.blue))
    if(!compact&&line.rare!=null&&line.maximum!=null&&line.rare>line.maximum)Text("Rare-roll limit ${s.format(line.rare)} · odds unknown",style=zen(10.sp,color=C.muted))
}

@Composable
private fun RawRolls(rolls:List<RollReview.Roll>?,starting:Boolean=false) {
    val shown=rolls?.filter { if(starting)!it.upgrade else it.upgrade }
    if(shown==null)Text("No reliable roll-by-roll history for this piece.",style=zen(11.sp,color=C.muted))
    else shown.forEach { roll->
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(if(roll.enhance==0)"Start" else "+${roll.enhance}${if(roll.reveal)" new" else ""}",style=zen(10.sp,color=C.muted),modifier=Modifier.width(46.dp))
            Text("${roll.stat.short} +${roll.stat.format(roll.value)}",style=zen(12.sp),modifier=Modifier.weight(1f))
            Text("usual ${roll.stat.format(roll.range.first.toDouble())}–${roll.stat.format(roll.range.last.toDouble())}",style=zen(10.sp,color=C.muted))
        }
    }
}

@Composable
private fun SafeguardReason(guard:RollReview.Safeguard,active:Boolean) {
    val target="${guard.stat.short} ${guard.stat.format(guard.minimum)}"
    val text=when { guard.now->"Already has $target";!guard.known->"$target: future unknown";guard.chance>0->"$target: ${GearPotential.percent(guard.chance)} chance";guard.possible->"$target: rare rolls could reach it; odds unknown";else->"$target: cannot reach in this forecast" }
    Text(text,style=zen(11.sp,color=if(active)C.blue else C.muted))
}

@Composable
fun SellFiltersScreen(model:AppModel) {
    val q=model.sellQuery;val result=model.sellResult
    var tab by remember { mutableIntStateOf(if(result==null)0 else 1) }
    var count by remember(result) { mutableIntStateOf(20) }
    var category by remember(result) { mutableIntStateOf(0) }
    LaunchedEffect(result) { if(result!=null)tab=1 }
    Frame(null,null,actions={
        if(model.sellBusy)OutlineButton("Stop",Modifier.weight(1f)) { model.stopSellSearch() }
        else if(tab==1&&result!=null&&result.matches.any { it.item.id !in model.reviewedIds }&&model.sellFresh&&category==0) {
            OutlineButton("Edit filters",Modifier.weight(1f)) { tab=0 }
            PinkButton("Review over game",Modifier.weight(1f),short="Over game") { model.trackSellMatches() }
        }else PinkButton(if(result!=null&&!model.sellFresh)"Update matches" else "Find matches",Modifier.weight(1f)) { model.findSellGear() }
    }) {
        BackToPrevious(model);ScreenTitle("Sell review")
        Text("Lowest rolls first · sell or keep each piece",style=zen(14.sp))
        Segmented(listOf("Set up","Matches"),tab) { tab=it }
        if(model.sellBusy)Card(shadow=null,padding=PaddingValues(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) { Spinner();Text("Checking ${model.sellDone} / ${model.sellTotal}",style=zen(13.sp)) }
            Text("Stop and resume from completed pieces.",style=zen(11.sp,color=C.muted))
        }
        model.sellError?.let { Text(it,style=zen(12.sp,color=C.deepPink)) }
        if(tab==0) {
            RowCard("Worth rolling",{openSell(model,SheetKind.SELL_WORTH)}) { Text("1 in ${if(q.rollOn>0)kotlin.math.round(100/q.rollOn).toInt().toString() else "–"} reach ${if(q.esBars.isEmpty())"your usual +15 ES" else "your ES bars"}",style=zen(11.sp)) }
            RowCard("Rolls",{openSell(model,SheetKind.SELL_QUALITY)}) { Text("${when(q.maximumTotalQuality) { null->"Any";50.0->"Low average";25.0->"Very low average";0.0->"Minimum only";else->"Up to ${fmtNum(q.maximumTotalQuality)}% of range" }}${if(q.minimumProcs>0)" · ${q.minimumProcs}+ upgrades" else ""}",style=zen(12.sp)) }
            RowCard("Stat safeguards",{openSell(model,SheetKind.SELL_SAFEGUARDS)}) { Text(if(q.keepStats.isEmpty())"Choose stats to leave out" else q.keepStats.entries.joinToString { (s,v)->"${s.short} ${s.format(v)}" },style=zen(11.sp)) }
            RowCard("Gear to review",{openSell(model,SheetKind.SELL_INVENTORY)}) { Text("${if(q.bagOnly)"Bag" else "All gear"} · +${q.minimumEnhance} to +${q.maximumEnhance}",style=zen(12.sp)) }
            RowCard("Slots",{openSell(model,SheetKind.SELL_SLOTS)}) { Text(if(q.slots.size==6)"Any slot" else q.slots.joinToString { it.label },style=zen(11.sp)) }
            RowCard("Sets",{openSell(model,SheetKind.SELL_SETS)}) { Text(if(q.sets.isEmpty())"Any set" else "${q.sets.size} selected",style=zen(12.sp)) }
            RowCard("Main stats",{openSell(model,SheetKind.SELL_MAINS)}) { Text(if(q.mains.isEmpty())"Any main" else q.mains.joinToString { it.short },style=zen(11.sp)) }
            RowCard("Levels",{openSell(model,SheetKind.SELL_LEVELS)}) { Text(if(q.levels.isEmpty())"Any level" else q.levels.sorted().joinToString(),style=zen(12.sp)) }
            SectionTitle("Order")
            Segmented(listOf("Lowest rolls","Game order"),if(q.sort==SellSort.INVENTORY)1 else 0) { model.updateSellQuery(q.copy(sort=if(it==0)SellSort.QUALITY else SellSort.INVENTORY,descending=false)) }
            if(q.scoreFilter)Text("Combined-score filter is on (Additional filters).",style=zen(11.sp,color=C.muted))
            RowCard("More filters",{openSell(model,SheetKind.SELL_EXTRA)}) { Text(if(q.scoreFilter)"Combined score on" else "Unknown pieces, coverage, order",style=zen(11.sp)) }
            RowCard("Kept pieces",{model.go(Screen.SellKept)}) { Text("${model.sellKept.size} kept",style=zen(12.sp)) }
        }else if(result==null)Card {
            Text("Find gear to review",style=dela(17.sp));VSpace(7.dp)
            Text("No minimum combined score is needed. Upgrade values are compared with that stat's own roll range.",style=zen(12.sp,color=C.muted))
        }else {
            val done=model.reviewedIds;val pending=result.matches.filter { it.item.id !in done }
            val guarded=result.rows.filter { it.protected&&it.item.id !in done };val unknown=result.rows.filter { it.unknown&&!it.protected&&it.item.id !in done }
            Card(shadow=null,padding=PaddingValues(12.dp)) {
                Text("${pending.size} pieces to review",style=dela(19.sp))
                Text("${if(result.query.sort==SellSort.INVENTORY)"Game order" else if(result.query.descending)"Best rolls first" else "Worst rolls first"} · keep rolling at 1 in ${if(result.query.rollOn>0)kotlin.math.round(100/result.query.rollOn).toInt().toString() else "–"}${if(result.query.reforge)" · reforge counted" else ""}",style=zen(11.sp,color=C.muted))
                if(result.query.minimumProcs>0||result.query.maximumTotalQuality!=null)Text("${if(result.query.minimumProcs>0)"${result.query.minimumProcs}+ upgrades · " else ""}${rollLimitText(result.query)}",style=zen(11.sp,color=C.blue))
                if(result.query.maximumProcQuality!=null)Text("An additional upgrade-only limit is active",style=zen(11.sp,color=C.muted))
                if(result.query.scoreFilter)Text("Additional combined-value filter applied",style=zen(11.sp,color=C.muted))
                if(guarded.isNotEmpty()||unknown.isNotEmpty()||model.sellKept.isNotEmpty()) {
                    VSpace(8.dp)
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        if(guarded.isNotEmpty())Pill("${guarded.size} kept by safeguards",selected=category==1,onClick={ category=if(category==1)0 else 1;count=20 })
                        if(unknown.isNotEmpty())Pill("${unknown.size} no forecast",selected=category==2,onClick={ category=if(category==2)0 else 2;count=20 })
                        if(model.sellKept.isNotEmpty())Pill("${model.sellKept.size} kept",onClick={ model.go(Screen.SellKept) })
                    }
                    if(category==2) { VSpace(6.dp);Text("No reliable roll history (event or preset gear, modified substats), so no chance can be shown. Tap again to go back.",style=zen(11.sp,color=C.muted)) }
                    if(category==1) { VSpace(6.dp);Text("These can still reach a stat you protect. Tap again to go back.",style=zen(11.sp,color=C.muted)) }
                }
            }
            if(!model.sellFresh&&!model.sellBusy)Text("Inventory or settings changed. Update before starting a list.",style=zen(12.sp,color=C.deepPink))
            val shown=when(category) { 1->guarded;2->unknown;else->pending }
            if(shown.isEmpty())Card {
                Text("Nothing matches",style=dela(17.sp));VSpace(6.dp)
                Text("Review the upgrade-count, roll limits, stat safeguards and gear filters.",style=zen(12.sp,color=C.muted))
            }
            shown.take(count).forEach { row->Card(shadow=null,radius=14.dp,border=2.dp,padding=PaddingValues(12.dp)) {
                Column(Modifier.tap { openSell(model,SheetKind.SELL_DETAILS,row.item.id) }) {
                    SellCardHead(model,row)
                    VSpace(10.dp)
                    row.es?.let { es->
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("ES ${es.now}",style=dela(17.sp))
                            if(es.finishShare>0)es.typical?.let { Text("  → ${it.toInt()}",style=zen(14.sp,color=C.muted)) }
                            Spacer(Modifier.weight(1f));EsChip(es,result.query.rollOn)
                        }
                        VSpace(6.dp);EsBar(es)
                    }?:Text("Update the list to see ES",style=zen(11.sp,color=C.muted))
                    if(row.protected)row.safeguards.filter { it.active(row.keepChance) }.forEach { SafeguardReason(it,true) }
                }
                if(category==0) {
                    VSpace(9.dp)
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        OutlineButton("Keep",Modifier.weight(1f),height=40.dp) { model.markSellReview(row.item.id,keep=true) }
                        OutlineButton("Sold",Modifier.weight(1f),height=40.dp) { model.markSellReview(row.item.id,keep=false) }
                    }
                }
            } }
            if(count<shown.size)OutlineButton("Show 20 more",Modifier.fillMaxWidth(),height=42.dp) { count+=20 }
            Text("Sell in Epic Seven first, then tap Sold.",style=zen(11.sp,color=C.muted))
        }
    }
}

@Composable
private fun SellToggle(title:String,on:Boolean,sub:String?=null,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().tap { change(!on) },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Text(title,style=zen(12.sp));sub?.let { Text(it,style=zen(10.sp,color=C.muted)) } };Toggle(on,change)
    }
}
@Composable
private fun SellNumber(title:String,value:Double?,minimum:Double,maximum:Double,precision:Int=1,unset:Boolean=false,change:(Double?)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(title,style=zen(12.sp),modifier=Modifier.weight(1f))
        NumberValue(value?:Double.NaN,title,minimum,maximum,Modifier.width(92.dp),precision=precision,allowUnset=unset) { change(it.takeIf(Double::isFinite)) }
    }
}
@Composable
private fun SellChoice(text:String,modifier:Modifier=Modifier,selected:Boolean,strong:Boolean=false,click:()->Unit) {
    val shape=RoundedCornerShape(10.dp)
    Box(modifier.heightIn(min=42.dp).then(if(selected)Modifier.padding(top=3.dp) else Modifier.padding(bottom=3.dp).hardShadow(C.shadow,0.dp,3.dp,10.dp))
        .clip(shape).background(if(selected)C.lightPink else C.card).border(if(strong||selected)2.5.dp else 1.5.dp,C.edge,shape).tap(click).padding(7.dp),contentAlignment=Alignment.Center) {
        FitText(text,if(strong)dela(12.sp) else zen(12.sp))
    }
}

@Composable
fun SellSheet(model:AppModel,sheet:Sheet) {
    val q=model.sellQuery
    fun update(v:SellQuery)=model.updateSellQuery(v)
    val title=when(sheet.kind) {
        SheetKind.SELL_SCORE->"How points work";SheetKind.SELL_WEIGHTS->"Stat weights";SheetKind.SELL_FLOORS->"Your comparison gear";SheetKind.SELL_CUSTOMS->"Custom comparisons";SheetKind.SELL_HEROES->"Comparison heroes"
        SheetKind.SELL_INVENTORY->"Gear to review";SheetKind.SELL_SLOTS->"Slots";SheetKind.SELL_SETS->"Sets";SheetKind.SELL_MAINS->"Main stats"
        SheetKind.SELL_LEVELS->"Equipment levels";SheetKind.SELL_QUALITY->"Low roll values";SheetKind.SELL_DETAILS->"Rolls & stat potential";SheetKind.SELL_SAFEGUARDS->"Stat safeguards";SheetKind.SELL_EXTRA->"More filters";SheetKind.SELL_WORTH->"When to keep rolling";else->"How gear is compared"
    }
    key(sheet.kind) {
    SheetBody(title,button=when(sheet.kind) { SheetKind.SELL_WEIGHTS->"Back to scoring";SheetKind.SELL_CUSTOMS->"Back to comparisons";else->"Done" },onButton={when(sheet.kind) { SheetKind.SELL_HEROES,SheetKind.SELL_CUSTOMS->openSell(model,SheetKind.SELL_FLOORS);SheetKind.SELL_WEIGHTS->openSell(model,SheetKind.SELL_SCORE);else->model.sheet=null }}) {
        when(sheet.kind) {
            SheetKind.SELL_EXTRA->{
                SellToggle("Show unknown forecasts",q.includeUnknown,"Missing roll history and incomplete checks stay unknown") { update(q.copy(includeUnknown=it)) }
                SellToggle("Standard Lv85 coverage",q.standardOnly,"Off assumes ordinary rolls on supported Lv85/88 gear, including event scenarios") { update(q.copy(standardOnly=it)) }
                SellToggle("Best rolls first",q.descending) { update(q.copy(descending=it)) }
                LinkText("Reset all sell filters") { update(SellQuery()) }
                SectionTitle("Combined score")
                SellToggle("Also filter by combined score",q.scoreFilter,"Optional comparison with your equipped gear") { update(q.copy(scoreFilter=it)) }
                Text("Off: use observed upgrade rolls and stat safeguards only. Existing score settings are retained here.",style=zen(11.sp,color=C.muted))
                LinkText("Scoring weights & diversity") { openSell(model,SheetKind.SELL_SCORE) }
                LinkText("Equipped comparison gear") { openSell(model,SheetKind.SELL_FLOORS) }
                SellNumber("Maximum catch-up chance %",q.maximumChance,0.0,100.0,1) { update(q.copy(maximumChance=it?:5.0)) }
                SellNumber("Maximum current score",q.maximumCurrentScore,0.0,model.sellScoreLimit,4,true) { update(q.copy(maximumCurrentScore=it)) }
                SellToggle("Include rare-only possibilities",q.includeRare,"Zero ordinary chance; rare probability unknown") { update(q.copy(includeRare=it)) }
                SellNumber("Per-piece time limit (ms)",q.forecastMs.toDouble(),25.0,5000.0,0) { update(q.copy(forecastMs=(it?:250.0).toInt())) }
                if(q.scoreFilter)CardGrid(SellSort.entries,columns=2) { key,m->SellChoice(when(key){SellSort.QUALITY->"Roll values";SellSort.INVENTORY->"Game order";SellSort.CHANCE->"Combined chance";SellSort.SCORE->"Combined score"},m,q.sort==key) { update(q.copy(sort=key)) } }
            }
            SheetKind.SELL_WORTH->{
                val n=if(q.rollOn>0)kotlin.math.round(100/q.rollOn).toInt() else 0
                Text("Keep enhancing a piece if at least this many of its finishes reach your ES bar",style=zen(13.sp))
                CardGrid(listOf(50,20,10,5),columns=4) { k,m->Column(m,horizontalAlignment=Alignment.CenterHorizontally) {
                    SellChoice("1 in $k",Modifier.fillMaxWidth(),n==k,strong=k==20) { update(q.copy(rollOn=100.0/k)) }
                    Text(if(k==20)"default" else " ",style=zen(10.sp,color=C.muted))
                } }
                if(n in 1..50)OddsDots(n)
                var why by remember { mutableStateOf(false) }
                Fold("What each choice costs",why,{ why=!why }) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Spacer(Modifier.width(66.dp))
                        Text("Good pieces kept",style=zen(11.sp,color=C.blue),modifier=Modifier.weight(1f))
                        Text("Each extra one costs",style=zen(11.sp,color=C.deepPink),modifier=Modifier.width(118.dp))
                    }
                    listOf(Triple("1 in 5",64,"—"),Triple("1 in 10",74,"3 enhances"),Triple("1 in 20",79,"4.6 enhances"),Triple("1 in 50",83,"7 enhances"),Triple("Finish all",85,"~300 enhances")).forEach { (k,good,extra)->
                        KeptRow(k,good,85,extra,k=="1 in ${n}")
                    }
                    Text("From a real inventory: 921 unfinished pieces, 85 of which can reach the bar. “Each extra one” is the full +0 → +15 enhances spent per good piece this choice keeps beyond the stricter one above it. Pick by how much enhance material you can spare.",style=zen(11.sp,color=C.muted))
                }
                SellNumber("Exact %",q.rollOn,0.0,100.0,1) { update(q.copy(rollOn=it?:5.0)) }
                SellToggle("Count reforging",q.reforge,"Lv85 gear reaches higher ES after reforge") { update(q.copy(reforge=it)) }
                SectionTitle("ES bar")
                Text("Default: the middle ES of your own +15 pieces in that slot.",style=zen(11.sp,color=C.muted))
                val auto=model.sellAutoEsBars()
                Slot.entries.forEach { slot->
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        GearIcon(slot.icon,18.dp,C.ink)
                        Box(Modifier.weight(1f)) { SellNumber("${slot.label}${if(slot in q.esBars)" · yours" else ""}",(q.esBars[slot]?:auto[slot])?.toDouble(),0.0,300.0,0,true) { v->
                            update(q.copy(esBars=if(v==null)q.esBars-slot else q.esBars+(slot to v.toInt()))) } }
                    }
                }
                if(q.esBars.isNotEmpty())OutlineButton("Use the defaults for every slot",Modifier.fillMaxWidth(),height=40.dp) { update(q.copy(esBars=emptyMap())) }
            }
            SheetKind.SELL_SAFEGUARDS->{
                Text("Leave a piece out of the review list if ANY substat can reach a target you choose. Main stats do not count.",style=zen(12.sp))
                if(q.keepStats.isEmpty())Text("No stat safeguards selected. Individual stats will still be shown for review.",style=zen(11.sp,color=C.muted))
                q.keepStats.forEach { (stat,value)->
                    val bound=GearBounds.target(stat,q.reforge,true)
                    SellNumber("Keep ${stat.short}",value,bound.minimum,maxOf(bound.maximum,value),1,true) { v->update(q.copy(keepStats=if(v==null)q.keepStats-stat else q.keepStats+(stat to v))) }
                }
                SectionTitle("Add a stat")
                CardGrid(GearStat.entries.filter { it !in q.keepStats },columns=3) { stat,m->SellChoice(stat.short,m,false) {
                    val bound=GearBounds.target(stat,q.reforge,true)
                    val suggested=model.potentialQuery.targets[stat]?.toDoubleOrNull()?.takeIf { it>0 }?:bound.minimum
                    update(q.copy(keepStats=q.keepStats+(stat to suggested)))
                } }
                LinkText("Use saved stat-search targets") {
                    val targets=model.potentialQuery.targets.mapNotNull { (stat,value)->value.toDoubleOrNull()?.takeIf { it>0 }?.let { stat to it } }.toMap()
                    update(q.copy(keepStats=q.keepStats+targets))
                }
                SellNumber("Minimum chance to leave out %",q.keepChance,0.0,100.0,1) { update(q.copy(keepChance=it?:0.0)) }
                Text("0% means any possible finish, including rare rolls with unknown odds. A higher limit uses ordinary-roll probability. Already-met targets always stay out.",style=zen(11.sp,color=C.muted))
                LinkText("Clear stat safeguards") { update(q.copy(keepStats=emptyMap())) }
            }
            SheetKind.SELL_SCORE->ScoringControls(model)
            SheetKind.SELL_WEIGHTS->{
                Text("Points for 1 stat unit. For example, Speed ×2 gives 20 points for 10 Speed. Both candidate and comparison pieces use these weights.",style=zen(12.sp))
                SellNumber("Main contribution %",q.score.mainPercent.toDouble(),0.0,500.0,0) { update(q.copy(score=q.score.copy(mainPercent=(it?:100.0).toInt()))) }
                GearStat.entries.forEach { stat->SellNumber("Weight ${stat.short}",q.score.weight(stat),0.0,10.0,4) { value->update(q.copy(score=q.score.copy(weights=q.score.weights+(stat to (value?:GearScore.weights.getValue(stat)))))) } }
                LinkText("Reset score model") { update(q.copy(score=GearScore.Model())) }
            }
            SheetKind.SELL_FLOORS->{
                Text("For each slot, start with the lowest-point piece equipped on your selected heroes. Any custom scores replace those comparisons below.",style=zen(12.sp))
                if(q.matchMain)Text("Same-main comparison is on. The matching main stat is selected separately for each result.",style=zen(11.sp,color=C.blue))
                var sources by remember { mutableStateOf(false) }
                OutlineButton(if(sources)"Hide hero selection" else "Change heroes & comparison",Modifier.fillMaxWidth(),height=42.dp) { sources=!sources }
                if(sources) {
                    Text("Use gear equipped on",style=zen(12.sp))
                    Segmented(listOf("Pool","Everyone","Chosen"),q.reference.ordinal) { update(q.copy(reference=SellReference.entries[it])) }
                    LinkText("Choose reference heroes (${q.referenceHeroes.size})") { openSell(model,SheetKind.SELL_HEROES) }
                    Text("Pool uses the heroes included in your pool run. Everyone uses all equipped gear. Chosen uses only the heroes you select.",style=zen(11.sp,color=C.muted))
                    SellNumber("Minimum equipped +",q.referenceMinimum.toDouble(),0.0,15.0,0) { update(q.copy(referenceMinimum=(it?:0.0).toInt())) }
                    Text("At 0, unfinished equipped pieces count too. At 15, only finished pieces supply the comparison.",style=zen(11.sp,color=C.muted))
                    SellToggle("Match main stat",q.matchMain,"Compare the same slot and main type") { update(q.copy(matchMain=it)) }
                    SellNumber("Comparison scale %",q.floorPercent,0.0,500.0,1) { update(q.copy(floorPercent=it?:100.0)) }
                    Text("100% uses the equipped score as-is. 110% asks unfinished gear to reach 10% above it.",style=zen(11.sp,color=C.muted))
                }
                Slot.entries.forEach { BenchmarkCard(model,it) }
                LinkText("Set custom scores per slot") { openSell(model,SheetKind.SELL_CUSTOMS) }
            }
            SheetKind.SELL_CUSTOMS->{
                Text("Leave a slot unset to use its equipped comparison. Enter a score to replace it. Your comparison scale still applies.",style=zen(12.sp))
                Slot.entries.forEach { slot->
                    SellNumber("${slot.label} comparison",q.floors[slot],0.0,model.sellScoreLimit,4,true) { v->update(q.copy(floors=if(v==null)q.floors-slot else q.floors+(slot to v))) }
                }
                LinkText("Use equipped scores for all slots") { update(q.copy(floors=emptyMap())) }
            }
            SheetKind.SELL_HEROES->{
                var name by remember { mutableStateOf("") };NameFilter(name,"Name") { name=it }
                Text("Use the weakest equipped piece per slot among these heroes.",style=zen(11.sp,color=C.muted))
                Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                    LinkText("Use pool selection") { update(q.copy(reference=SellReference.SELECTED,referenceHeroes=model.poolMembers().map { it.id }.toSet())) }
                    LinkText("Clear") { update(q.copy(referenceHeroes=emptySet())) }
                }
                model.box?.allHeroes.orEmpty().filter { nameMatches(model.heroName(it),name) }.sortedBy(model::heroName).forEach { h->
                    val selected=h.id in q.referenceHeroes
                    Row(Modifier.fillMaxWidth().tap { update(q.copy(reference=SellReference.SELECTED,referenceHeroes=if(selected)q.referenceHeroes-h.id else q.referenceHeroes+h.id)) }.padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                        Face(model.face(h),28.dp);Text(model.heroName(h),style=zen(12.sp),modifier=Modifier.weight(1f));Checkbox(selected) { on->update(q.copy(reference=SellReference.SELECTED,referenceHeroes=if(on)q.referenceHeroes+h.id else q.referenceHeroes-h.id)) }
                    }
                }
            }
            SheetKind.SELL_INVENTORY->{
                SellToggle("In bag only",q.bagOnly) { update(q.copy(bagOnly=it)) }
                SellToggle("Include locked gear",q.includeLocked) { update(q.copy(includeLocked=it)) }
                SellToggle("Protect gear on pool heroes",q.protectPool) { update(q.copy(protectPool=it)) }
                SellNumber("Minimum enhancement",q.minimumEnhance.toDouble(),0.0,q.maximumEnhance.toDouble(),0) { update(q.copy(minimumEnhance=(it?:0.0).toInt())) }
                SellNumber("Maximum enhancement",q.maximumEnhance.toDouble(),q.minimumEnhance.toDouble(),15.0,0) { update(q.copy(maximumEnhance=(it?:14.0).toInt())) }
            }
            SheetKind.SELL_SLOTS->{
                LinkText("Any slot") { update(q.copy(slots=Slot.entries.toSet())) }
                CardGrid(Slot.entries,columns=3) { s,m->SellChoice(s.label,m,s in q.slots) { update(q.copy(slots=if(s in q.slots)q.slots-s else q.slots+s)) } }
            }
            SheetKind.SELL_SETS->{
                var name by remember { mutableStateOf("") };NameFilter(name,"Name") { name=it }
                LinkText("Any set") { update(q.copy(sets=emptySet())) }
                CardGrid(model.content.sets.filter { nameMatches(it.name,name) },columns=3) { s,m->
                    Column(m, horizontalAlignment=Alignment.CenterHorizontally) { SetIcon(s.icon,25.dp);SellChoice(s.name,Modifier.fillMaxWidth(),s.key in q.sets) { update(q.copy(sets=if(s.key in q.sets)q.sets-s.key else q.sets+s.key)) } }
                }
            }
            SheetKind.SELL_MAINS->{
                LinkText("Any main") { update(q.copy(mains=emptySet())) }
                CardGrid(GearStat.entries,columns=3) { s,m->SellChoice(s.short,m,s in q.mains) { update(q.copy(mains=if(s in q.mains)q.mains-s else q.mains+s)) } }
            }
            SheetKind.SELL_LEVELS->{
                LinkText("Any level") { update(q.copy(levels=emptySet())) }
                CardGrid(model.box?.items.orEmpty().map { it.level }.distinct().sorted(),columns=4) { n,m->SellChoice("Lv.$n",m,n in q.levels) { update(q.copy(levels=if(n in q.levels)q.levels-n else q.levels+n)) } }
            }
            SheetKind.SELL_QUALITY->{
                Text("Choose overall roll quality. Starting values, upgrade hits and Heroic new-line rolls all count, using each stat’s own usual range.",style=zen(12.sp))
                CardGrid(listOf("Any" to null,"Low average" to 50.0,"Very low average" to 25.0,"Minimum only" to 0.0),columns=2) { (label,limit),m->SellChoice(label,m,q.maximumTotalQuality==limit) { update(q.copy(maximumTotalQuality=limit)) } }
                Text(rollLimitText(q),style=zen(12.sp,color=C.blue))
                Text("Low average: at or below the middle of each stat's range. For example, Speed +3 in 2–4 and HP% +6 in 4–8 are both midrange on Lv85 gear.",style=zen(11.sp,color=C.muted))
                SellNumber("Minimum upgrades recorded",q.minimumProcs.toDouble(),0.0,5.0,0) { update(q.copy(minimumProcs=(it?:2.0).toInt())) }
                if(q.maximumProcQuality!=null)Text("An additional upgrade-only limit is active: ${fmtNum(q.maximumProcQuality)}% of range.",style=zen(11.sp,color=C.blue))
                var custom by remember { mutableStateOf(false) }
                LinkText(if(custom)"Hide custom roll limits" else "Custom roll limits") { custom=!custom }
                if(custom) {
                    Text("0% is the minimum; 50% is halfway; 100% is the usual maximum. Rare endpoints can exceed 100%.",style=zen(11.sp,color=C.muted))
                    SellNumber("Upgrade-only limit %",q.maximumProcQuality,0.0,200.0,1,true) { update(q.copy(maximumProcQuality=it)) }
                    SellNumber("Overall roll limit %",q.maximumTotalQuality,0.0,200.0,1,true) { update(q.copy(maximumTotalQuality=it)) }
                }
                Text("Overall ranking includes starting values and Heroic fourth-line reveals. Upgrade-only values remain a separate breakdown. Corrections or modified histories can be unknown.",style=zen(11.sp,color=C.muted))
                LinkText("No extra roll-quality limits") { update(q.copy(maximumProcQuality=null,maximumTotalQuality=null)) }
            }
            SheetKind.SELL_DETAILS->{
                val row=model.sellResult?.rows?.firstOrNull { it.item.id==sheet.heroId }
                if(row==null)Text("Update the search to see this piece.",style=zen(12.sp)) else {
                    SellCardHead(model,row)
                    row.es?.let { es->
                        VSpace(4.dp)
                        Card(shadow=null,padding=PaddingValues(12.dp)) {
                            val open=es.finishShare>0&&es.bar!=null&&!es.reached&&es.supported
                            Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                val rare=open&&es.chance==0.0&&es.possible
                                Text(if(rare)"Rare" else if(open)chanceText(es.chance) else "ES ${es.now}",style=dela(28.sp,if(!open)C.ink else if(es.worthRolling(model.sellResult!!.query.rollOn))C.blue else C.deepPink))
                                Text(when { es.bar==null->"no bar for this slot";rare->"only rare rolls reach ES ${es.bar}";open->"of finishes reach ES ${es.bar}";es.reached->"at or above your bar ${es.bar}";else->"finished · below bar ${es.bar}" },style=zen(12.sp,color=C.muted),modifier=Modifier.padding(bottom=5.dp))
                            }
                            VSpace(8.dp);EsBar(es);VSpace(3.dp);EsLegend(es)
                            if(es.finishShare>0) { VSpace(4.dp);Text("Finishing costs ${(es.finishShare*100).toInt()}% of a full +0 → +15",style=zen(10.sp,color=C.muted)) }
                        }
                    }
                    SectionTitle("Rolls",trailing=row.rolls?.let { "${RollReview.overallWeakCount(it)} of ${it.size} low" })
                    RollTable(row.rolls)
                    var stats by remember(row.item.id) { mutableStateOf(false) }
                    Fold("Each stat at +15",stats,{ stats=!stats }) { row.lines.sortedBy { if(it.stat==GearStat.SPD)0 else 1 }.forEach { StatFinish(it,model.sellResult!!.query.reforge) } }
                    if(row.safeguards.isNotEmpty()) { SectionTitle("Your stat safeguards");row.safeguards.forEach { SafeguardReason(it,it.active(row.keepChance)) } }
                    if(!model.sellFresh)Text("Inventory or settings changed; update before tracking.",style=zen(12.sp,color=C.deepPink))
                    model.inventoryPosition(row.item.id)?.let { pos->Text("In game: ${pos.grid()} · ${if(model.inventoryAscending)"low score first" else "high score first"}",style=zen(11.sp,color=C.muted)) }
                    if(model.sellResult?.matches?.any { it.item.id==row.item.id }==true)OutlineButton("Track over game",Modifier.fillMaxWidth(),height=44.dp,enabled=model.sellFresh) { model.trackSellMatches(row.item.id) }
                    var combined by remember(row.item.id) { mutableStateOf(false) }
                    if(model.sellResult!!.query.scoreFilter)Fold("Combined score",combined,{ combined=!combined }) {
                        val forecast by produceState(row.forecast,row.item.id) { if(!row.scoreUsed)value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { GearScore.forecast(row.item,row.goal) } }
                        Text("Optional weighted comparison",style=dela(15.sp))
                        ScoreBreakdown(row.item,row.goal.model,true)
                        FinishComparison(row.copy(forecast=forecast))
                        Text(referenceText(row.goal),style=zen(11.sp,color=C.muted))
                        forecast.reason?.let { Text(it,style=zen(11.sp,color=C.muted)) }
                    }
                }
            }
            else->{
                Text("Sell filters find pieces matching your chosen limits. They do not prove a piece should be sold.",style=zen(13.sp))
                SectionTitle("Points")
                Text("Main and substats become points using your chosen weights. For example, 10 Speed × 2 gives 20 points. The optional diversity penalty discounts points outside the strongest substat group. Flat and percent versions of a stat share a group. Main points are separate.",style=zen(12.sp))
                SectionTitle("Comparison gear")
                Text("For each slot, use the lowest-point piece already equipped on your selected heroes. That is the score unfinished gear must reach. See the hero and item under Compare with. Changing weights or diversity also changes these comparison scores. You can choose finished pieces only or enter your own score.",style=zen(12.sp))
                SectionTitle("Chance")
                Text("The ordinary-roll model gives legal roll destinations and ordinary values equal weight, including Heroic +12 and optional reforging. Shared allocations are counted together. Rare endpoint possibilities have no estimated percentage. Unknowns and time limits are never treated as zero chance.",style=zen(12.sp))
                SectionTitle("Current inventory")
                Text("Recorded upgrades are included. Cleared gear cannot be a candidate or comparison piece. Update the search after changes. Your settings are backed up.",style=zen(12.sp))
                SectionTitle("Coverage")
                Text("Standard coverage uses supported Lv85 histories. Broader coverage is an ordinary-roll scenario for Lv85/88; some event gear uses fixed rolls. Finished gear can be scored at any level. The time limit is editable; incomplete checks stay unknown.",style=zen(12.sp))
            }
        }
    }
    }
}
