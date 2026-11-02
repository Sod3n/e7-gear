package e7.gearscan.features.sellreview

import androidx.compose.runtime.saveable.rememberSaveable
import e7.gearscan.state.then
import e7.gearscan.ui.*

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

private fun AppGraph.findSell() = messages.show(sellReview.find())
fun AppGraph.trackMatches(id:Long?=null,openOverlay:Boolean=true):Outcome=sellReview.toTrack(id).then { (rows,query)->tracker.trackSellQueue(rows,query,openOverlay) }
private fun AppGraph.trackSell(id:Long?=null) = messages.show(trackMatches(id))
private fun AppGraph.markSell(id:Long,keep:Boolean) {
    val outcome=marks.markReviewed(id,keep)
    if(outcome is Outcome.Note)messages.showToast(outcome.text,"Undo") { marks.unmark(id) }
}

private fun openSell(m: AppGraph,k:SheetKind,id:Long=0) { m.nav.sheet=Sheet(k,id) }
private fun scoreText(v:Double)=String.format(java.util.Locale.US,"%.4f",v).trimEnd('0').trimEnd('.')
private fun rollLimitText(q:SellQuery)=when(q.maximumTotalQuality) {
    null->"All overall roll values";0.0->"Only minimum rolls";25.0->"Average in bottom quarter";50.0->"Average at/below midrange";100.0->"Average up to usual maximum";else->"Average within lower ${fmtNum(q.maximumTotalQuality!!)}% of range"
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
private fun ComparisonOwner(graph: AppGraph,item:Item) {
    val hero=item.equippedOn?.let(graph.heroes::hero)
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        if(hero!=null)Face(graph.heroes.face(hero),26.dp)
        Column(Modifier.weight(1f)) {
            Text(hero?.let(graph.heroes::heroName)?:"Equipped comparison piece",style=zen(12.sp))
            Text("+${item.enhance} · ${graph.content.content.set(item.set)?.name?:item.set.removeSuffix("Set")} ${item.slot.label}",style=zen(10.sp,color=C.muted))
        }
    }
}

@Composable
private fun BenchmarkCard(graph: AppGraph,slot:Slot) {
    val q=graph.sellReview.query;val auto=graph.sellReview.floorPreview(slot);val custom=q.floors[slot]
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
                ComparisonOwner(graph,auto.item!!)
                VSpace(5.dp)
                val b=q.score.breakdown(auto.item!!)
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
@OptIn(LinkStyle::class)
private fun ScoringControls(graph: AppGraph) {
    val q=graph.sellReview.query
    val example=remember(graph.scan.lastScan) { graph.sellReview.floorPreview(Slot.HELMET)?.item?:graph.scan.box?.items?.firstOrNull() }
    Text("Main stat + substats = points. Higher means more weighted stats; it does not measure a complete hero build.",style=zen(12.sp))
    SellNumber("Diversity penalty %",q.score.diversity.toDouble(),0.0,100.0,0) { graph.sellReview.edit(q.copy(score=q.score.copy(diversity=(it?:0.0).toInt()))) }
    CardGrid(listOf(0,25,50,100),columns=4) { n,m->SellChoice("$n%",m,q.score.diversity==n) { graph.sellReview.edit(q.copy(score=q.score.copy(diversity=n))) } }
    Text("0%: every substat counts in full. 100%: only the strongest substat group counts. The main stat is separate.",style=zen(11.sp,color=C.muted))
    if(example!=null)Card(shadow=null,padding=PaddingValues(12.dp)) {
        Text("See it on your gear",style=dela(15.sp))
        VSpace(8.dp);ComparisonOwner(graph,example);VSpace(8.dp)
        var calculation by remember { mutableStateOf(false) }
        ScoreBreakdown(example,q.score,calculation)
        Text("The comparison gear is rescored with these same settings.",style=zen(10.sp,color=C.muted))
        VSpace(6.dp);LinkText(if(calculation)"Hide point calculation" else "Show point calculation") { calculation=!calculation }
    }
    LinkText("Edit stat weights & main") { openSell(graph,SheetKind.SELL_WEIGHTS) }
    LinkText("Reset score model") { graph.messages.askReset("Reset the score model?","Weights and diversity go back to the game's values.",graph.sellReview::resetScoreModel) }
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
private fun SellCardHead(graph: AppGraph,row:SellSearch.Row) {
    val item=if(row.item.enhance<15)UpgradeRecord.fromScan(row.item) else row.item
    Row(verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top=4.dp)) { GearTile(item,46.dp,setIcon=graph.content.content.set(item.set)?.icon) }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("${graph.content.content.set(item.set)?.name?:item.set.removeSuffix("Set")} ${item.slot.label}",style=zen(13.sp),modifier=Modifier.weight(1f),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
    else Text(if(compact)"$label → up to ${s.format(line.maximum!!)}" else "$label now · ${s.format(line.minimum!!)}–${s.format(line.maximum!!)} at +15${if(reforge)" & eligible reforge" else ""}",style=zen(11.sp,color=C.blue))
    if(!compact&&line.rare!=null&&line.maximum!=null&&line.rare!!>line.maximum!!)Text("Rare-roll limit ${s.format(line.rare!!)} · odds unknown",style=zen(10.sp,color=C.muted))
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
fun SellFiltersScreen(graph: AppGraph) {
    val q=graph.sellReview.query;val result=graph.sellReview.result
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var count by rememberSavedFor(result, 20)
    var category by rememberSavedFor(result, 0)
    // only the main button moves to Matches; Show my numbers in a sheet keeps the player on Set up
    var jump by remember { mutableStateOf(false) }
    LaunchedEffect(result) { if(result!=null&&jump) { tab=1;jump=false } }
    LaunchedEffect(result==null) { if(result==null)tab=0 }
    val reviewing = tab==1&&result!=null&&result.matches.any { it.item.id !in graph.marks.reviewed }&&graph.sellReview.fresh&&category==0
    Frame(null,null,
        primary=when {
            graph.sellReview.busy -> null
            reviewing -> Action("Review over game",short="Over game") { graph.trackSell() }
            else -> Action(if(result!=null&&!graph.sellReview.fresh)"Update matches" else "Find matches") { jump=true;graph.findSell() }
        },
        secondary=when {
            graph.sellReview.busy -> Secondary("Stop",Span.Fill) { graph.sellReview.stop() }
            reviewing -> Secondary("Edit filters",Span.Fill,before=true) { tab=0 }
            else -> null
        }) {
        BackToPrevious();ScreenTitle("Sell review")
        Segmented(listOf("Set up","Matches"),tab) { tab=it }
        if(graph.sellReview.busy)Card(shadow=null,padding=PaddingValues(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) { Spinner();Text("Checking ${graph.sellReview.done} / ${graph.sellReview.total}",style=zen(13.sp)) }
        }
        if(tab==0) {
            RowCard("Stat safeguards",{openSell(graph,SheetKind.SELL_SAFEGUARDS)},labelWidth=112.dp) { Text(when { q.keepStats.isEmpty()->"Off";q.keepStats==SellQuery.DEFAULT_GUARDS->"Default · ${q.keepStats.size} stats";else->"${q.keepStats.size} stats" },style=zen(12.sp),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            RowCard("Gear to review",{openSell(graph,SheetKind.SELL_INVENTORY)},labelWidth=112.dp) { Text("${if(q.bagOnly)"Bag" else "All gear"} · +${q.minimumEnhance/3*3} to +${if(q.maximumEnhance>=15)15 else q.maximumEnhance/3*3}",style=zen(12.sp),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            RowCard("Slots",{openSell(graph,SheetKind.SELL_SLOTS)},labelWidth=112.dp) { Text(if(q.slots.size==6)"Any slot" else q.slots.joinToString { it.label },style=zen(12.sp),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            RowCard("Sets",{openSell(graph,SheetKind.SELL_SETS)},labelWidth=112.dp) { Text(if(q.sets.isEmpty())"Any set" else "${q.sets.size} selected",style=zen(12.sp),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            RowCard("Main stats",{openSell(graph,SheetKind.SELL_MAINS)},labelWidth=112.dp) { Text(if(q.mains.isEmpty())"Any main" else q.mains.joinToString { it.short },style=zen(12.sp),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            //levels filter removed (user 2026-11-01: useless in the current game)
            if(false)RowCard("Levels",{openSell(graph,SheetKind.SELL_LEVELS)},labelWidth=112.dp) { Text(if(q.levels.isEmpty())"Any level" else q.levels.sorted().joinToString(),style=zen(12.sp),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            RowCard("More filters",{openSell(graph,SheetKind.SELL_EXTRA)},labelWidth=112.dp) { Text(if(q.scoreFilter)"Combined score on" else "Off",style=zen(12.sp),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            RowCard("Kept pieces",{graph.nav.go(Screen.SellKept)},labelWidth=112.dp) { Text("${graph.marks.kept.size} kept",style=zen(12.sp),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            SectionTitle("Final step")
            Box(Modifier.fillMaxWidth().hardShadow(C.pink,4.dp,4.dp,14.dp)) {
                RowCard("Worth rolling",{openSell(graph,SheetKind.SELL_WORTH)},labelWidth=112.dp) { Text("1 in ${if(q.rollOn>0)kotlin.math.round(100/q.rollOn).toInt().toString() else "–"} reach ${if(q.esBars.isEmpty())"your usual +15 ES" else "your ES bars"}",style=zen(12.sp),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
        }else if(result==null)Card {
            Text("Tap Find matches",style=dela(17.sp))
        }else {
            val done=graph.marks.reviewed;val pending=result.matches.filter { it.item.id !in done }
            val guarded=result.rows.filter { it.protected&&it.item.id !in done };val unknown=result.rows.filter { it.unknown&&!it.protected&&it.item.id !in done }
            Card(shadow=null,padding=PaddingValues(12.dp)) {
                Text("${pending.size} pieces to review",style=dela(19.sp))
                val rolling=pending.mapNotNull { it.es }.filter { it.finishShare>0&&it.worthRolling(q.rollOn)&&!it.reached }
                if(rolling.isNotEmpty())Text("${rolling.size} worth rolling now",style=zen(12.sp,color=C.blue))
                if(result.query.minimumProcs>0||result.query.maximumTotalQuality!=null)Text("${if(result.query.minimumProcs>0)"${result.query.minimumProcs}+ upgrades · " else ""}${rollLimitText(result.query)}",style=zen(11.sp,color=C.blue))
                if(result.query.scoreFilter)Text("Additional combined-value filter applied",style=zen(11.sp,color=C.muted))
                if(guarded.isNotEmpty()||unknown.isNotEmpty()||graph.marks.kept.isNotEmpty()) {
                    VSpace(8.dp)
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        if(guarded.isNotEmpty())Pill("${guarded.size} kept by safeguards",selected=category==1,onClick={ category=if(category==1)0 else 1;count=20 })
                        if(unknown.isNotEmpty())Pill("${unknown.size} no forecast",selected=category==2,onClick={ category=if(category==2)0 else 2;count=20 })
                        if(graph.marks.kept.isNotEmpty())Pill("${graph.marks.kept.size} kept",onClick={ graph.nav.go(Screen.SellKept) })
                    }
                    if(category==2) { VSpace(6.dp);Text("No roll history, so no forecast.",style=zen(11.sp,color=C.muted)) }
                    if(category==1) { VSpace(6.dp);Text("Can still reach a stat you protect.",style=zen(11.sp,color=C.muted)) }
                }
            }
            if(!graph.sellReview.fresh&&!graph.sellReview.busy)Text("Inventory or settings changed. Update before starting a list.",style=zen(12.sp,color=C.deepPink))
            val shown=when(category) { 1->guarded;2->unknown;else->pending }
            if(shown.isEmpty())Card {
                Text("Nothing matches",style=dela(17.sp))
            }
            shown.take(count).forEach { row->Card(shadow=null,radius=14.dp,border=2.dp,padding=PaddingValues(12.dp)) {
                Column(Modifier.tap { openSell(graph,SheetKind.SELL_DETAILS,row.item.id) }) {
                    SellCardHead(graph,row)
                    VSpace(10.dp)
                    row.es?.let { es->
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("ES ${es.now}",style=dela(17.sp))
                            if(es.finishShare>0)es.typical?.let { Text("  → ${it.toInt()}",style=zen(14.sp,color=C.muted)) }
                            Spacer(Modifier.weight(1f));EsChip(es,q.rollOn)
                        }
                        VSpace(6.dp);EsBar(es)
                        if(es.finishShare>0) { VSpace(3.dp);Text("~${EsForecast.goldText(es.finishShare*EsForecast.FULL_GOLD)} gold to +15",style=zen(10.sp,color=C.muted)) }
                    }?:Text("Update the list to see ES",style=zen(11.sp,color=C.muted))
                    if(row.protected)row.safeguards.filter { it.active(row.keepChance) }.forEach { SafeguardReason(it,true) }
                }
                if(category==0) {
                    VSpace(9.dp)
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        OutlineButton("Keep",Modifier.weight(1f),height=40.dp) { graph.markSell(row.item.id,keep=true) }
                        OutlineButton("Sold",Modifier.weight(1f),height=40.dp) { graph.markSell(row.item.id,keep=false) }
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
private fun SellChoice(text:String,modifier:Modifier=Modifier,selected:Boolean,strong:Boolean=false,leading:(@Composable ()->Unit)?=null,click:()->Unit) {
    val shape=RoundedCornerShape(10.dp)
    Box(modifier.heightIn(min=42.dp).then(if(selected)Modifier.padding(top=3.dp) else Modifier.padding(bottom=3.dp).hardShadow(C.shadow,0.dp,3.dp,10.dp))
        .clip(shape).background(if(selected)C.lightPink else C.card).border(if(strong||selected)2.5.dp else 1.5.dp,C.edge,shape).tap(click).padding(7.dp),contentAlignment=Alignment.Center) {
        if(leading==null)FitText(text,if(strong)dela(12.sp) else zen(12.sp))
        else Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) { leading();FitText(text,if(strong)dela(12.sp) else zen(12.sp)) }
    }
}

@Composable
@OptIn(LinkStyle::class)
fun SellSheet(graph: AppGraph,sheet:Sheet) {
    val q=graph.sellReview.query
    fun update(v:SellQuery)=graph.sellReview.edit(v)
    val title=when(sheet.kind) {
        SheetKind.SELL_SCORE->"How points work";SheetKind.SELL_WEIGHTS->"Stat weights";SheetKind.SELL_FLOORS->"Your comparison gear";SheetKind.SELL_CUSTOMS->"Custom comparisons";SheetKind.SELL_HEROES->"Comparison heroes"
        SheetKind.SELL_INVENTORY->"Gear to review";SheetKind.SELL_SLOTS->"Slots";SheetKind.SELL_SETS->"Sets";SheetKind.SELL_MAINS->"Main stats"
        SheetKind.SELL_LEVELS->"Equipment levels";SheetKind.SELL_QUALITY->"Low roll values";SheetKind.SELL_DETAILS->"Rolls & stat potential";SheetKind.SELL_SAFEGUARDS->"Stat safeguards";SheetKind.SELL_EXTRA->"More filters";SheetKind.SELL_WORTH->"When to keep rolling";else->"How gear is compared"
    }
    key(sheet.kind) {
    SheetBody(title,button=when(sheet.kind) { SheetKind.SELL_WEIGHTS->"Back to scoring";SheetKind.SELL_CUSTOMS->"Back to comparisons";else->"Done" },onButton={when(sheet.kind) { SheetKind.SELL_HEROES,SheetKind.SELL_CUSTOMS->openSell(graph,SheetKind.SELL_FLOORS);SheetKind.SELL_WEIGHTS->openSell(graph,SheetKind.SELL_SCORE);else->graph.nav.sheet=null }}) {
        when(sheet.kind) {
            SheetKind.SELL_EXTRA->{
                SellToggle("Show pieces without a forecast",q.includeUnknown) { update(q.copy(includeUnknown=it)) }
                SellToggle("Lv85 crafted gear only",q.standardOnly) { update(q.copy(standardOnly=it)) }
                SellToggle("Also filter by combined score",q.scoreFilter) { update(q.copy(scoreFilter=it)) }
                if(q.scoreFilter) {
                    RowCard("Scoring",{openSell(graph,SheetKind.SELL_SCORE)}) { Text("Weights & diversity",style=zen(12.sp)) }
                    RowCard("Comparison gear",{openSell(graph,SheetKind.SELL_FLOORS)}) { Text("Equipped pieces",style=zen(12.sp)) }
                    SellNumber("Maximum catch-up chance %",q.maximumChance,0.0,100.0,1) { update(q.copy(maximumChance=it?:5.0)) }
                    SellNumber("Maximum current score",q.maximumCurrentScore,0.0,graph.sellReview.scoreLimit,4,true) { update(q.copy(maximumCurrentScore=it)) }
                    SellToggle("Include rare-only possibilities",q.includeRare) { update(q.copy(includeRare=it)) }
                }
                OutlineButton("Reset all Sell review settings",Modifier.fillMaxWidth(),height=40.dp) { graph.messages.askReset("Reset Sell review?","Every Sell review setting goes back to its default. Sold and kept marks stay.",graph.sellReview::resetSettings) }
            }
            SheetKind.SELL_WORTH->{
                KeepRollingSlider(q.rollOn,graph.sellReview.keepCurve,graph.sellReview.curveBusy||graph.sellReview.busy,{ graph.findSell() },{ t,b->graph.messages.explain(t,b) }) { update(q.copy(rollOn=it)) }
                SellToggle("Count reforging",q.reforge) { update(q.copy(reforge=it)) }
                SectionTitle("ES bar")
                val auto=graph.sellReview.autoEsBars()
                Slot.entries.forEach { slot->ValueSlider(slot.label,{ GearIcon(slot.icon,18.dp,C.ink) },(q.esBars[slot]?:auto[slot]?:80).toDouble(),EsForecast.esRange.first.toDouble(),EsForecast.esRange.last.toDouble(),1.0,{ "${it.toInt()}" }) { v->
                    update(q.copy(esBars=q.esBars+(slot to v.toInt()))) } }
                OutlineButton("Reset all to your +15 medians",Modifier.fillMaxWidth(),height=40.dp,enabled=q.esBars.isNotEmpty()) { graph.messages.askReset("Reset the ES bars?","Every slot goes back to the middle ES of your own +15 pieces.",graph.sellReview::resetEsBars) }
            }
            SheetKind.SELL_SAFEGUARDS->{
                Text("Pieces that can still reach one of these stay out of Sell review.",style=zen(13.sp))
                GearStat.entries.forEach { stat->
                    val bound=GearBounds.target(stat,q.reforge,true);val step=if(stat.showsPercent||stat==GearStat.SPD)1.0 else 10.0
                    // far left is off; anywhere else sets that value
                    ValueSlider(stat.short,{ StatIcon(stat.stat,16.dp,if(stat in q.keepStats)C.ink else C.muted) },q.keepStats[stat]?:(bound.minimum-step),bound.minimum,
                        maxOf(bound.maximum,q.keepStats[stat]?:bound.minimum),step,{ if(it<bound.minimum)"off" else stat.format(it) },offStop=true) { v->
                        update(q.copy(keepStats=if(v<bound.minimum)q.keepStats-stat else q.keepStats+(stat to v))) }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    OutlineButton("Turn all off",Modifier.weight(1f),height=40.dp,enabled=q.keepStats.isNotEmpty()) { update(q.copy(keepStats=emptyMap())) }
                    OutlineButton("Reset to defaults",Modifier.weight(1f),height=40.dp,enabled=q.keepStats!=SellQuery.DEFAULT_GUARDS||q.keepChance!=1.0) { graph.messages.askReset("Reset the safeguards?","Spd 20, Cr 25%, Cd 35% and 40% for every % stat; flat stats off.",graph.sellReview::resetSafeguards) }
                }
            }
            SheetKind.SELL_SCORE->ScoringControls(graph)
            SheetKind.SELL_WEIGHTS->{
                Text("Points for 1 stat unit. For example, Speed ×2 gives 20 points for 10 Speed. Both candidate and comparison pieces use these weights.",style=zen(12.sp))
                SellNumber("Main contribution %",q.score.mainPercent.toDouble(),0.0,500.0,0) { update(q.copy(score=q.score.copy(mainPercent=(it?:100.0).toInt()))) }
                GearStat.entries.forEach { stat->SellNumber("Weight ${stat.short}",q.score.weight(stat),0.0,10.0,4) { value->update(q.copy(score=q.score.copy(weights=q.score.weights+(stat to (value?:GearScore.weights.getValue(stat)))))) } }
                LinkText("Reset score model") { graph.messages.askReset("Reset the score model?","Weights and diversity go back to the game's values.",graph.sellReview::resetScoreModel) }
            }
            SheetKind.SELL_FLOORS->{
                Text("For each slot, start with the lowest-point piece equipped on your selected heroes. Any custom scores replace those comparisons below.",style=zen(12.sp))
                if(q.matchMain)Text("Same-main comparison is on. The matching main stat is selected separately for each result.",style=zen(11.sp,color=C.blue))
                var sources by remember { mutableStateOf(false) }
                OutlineButton(if(sources)"Hide hero selection" else "Change heroes & comparison",Modifier.fillMaxWidth(),height=42.dp) { sources=!sources }
                if(sources) {
                    Text("Use gear equipped on",style=zen(12.sp))
                    Segmented(listOf("Pool","Everyone","Chosen"),q.reference.ordinal) { update(q.copy(reference=SellReference.entries[it])) }
                    LinkText("Choose reference heroes (${q.referenceHeroes.size})") { openSell(graph,SheetKind.SELL_HEROES) }
                    Text("Pool uses the heroes included in your pool run. Everyone uses all equipped gear. Chosen uses only the heroes you select.",style=zen(11.sp,color=C.muted))
                    SellNumber("Minimum equipped +",q.referenceMinimum.toDouble(),0.0,15.0,0) { update(q.copy(referenceMinimum=(it?:0.0).toInt())) }
                    Text("At 0, unfinished equipped pieces count too. At 15, only finished pieces supply the comparison.",style=zen(11.sp,color=C.muted))
                    SellToggle("Match main stat",q.matchMain,"Compare the same slot and main type") { update(q.copy(matchMain=it)) }
                    SellNumber("Comparison scale %",q.floorPercent,0.0,500.0,1) { update(q.copy(floorPercent=it?:100.0)) }
                    Text("100% uses the equipped score as-is. 110% asks unfinished gear to reach 10% above it.",style=zen(11.sp,color=C.muted))
                }
                Slot.entries.forEach { BenchmarkCard(graph,it) }
                LinkText("Set custom scores per slot") { openSell(graph,SheetKind.SELL_CUSTOMS) }
            }
            SheetKind.SELL_CUSTOMS->{
                Text("Leave a slot unset to use its equipped comparison. Enter a score to replace it. Your comparison scale still applies.",style=zen(12.sp))
                Slot.entries.forEach { slot->
                    SellNumber("${slot.label} comparison",q.floors[slot],0.0,graph.sellReview.scoreLimit,4,true) { v->update(q.copy(floors=if(v==null)q.floors-slot else q.floors+(slot to v))) }
                }
                LinkText("Use equipped scores for all slots") { update(q.copy(floors=emptyMap())) }
            }
            SheetKind.SELL_HEROES->{
                var name by remember { mutableStateOf("") };NameFilter(name,"Name") { name=it }
                Text("Use the weakest equipped piece per slot among these heroes.",style=zen(11.sp,color=C.muted))
                Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                    LinkText("Use pool selection") { update(q.copy(reference=SellReference.SELECTED,referenceHeroes=graph.pool.members().map { it.id }.toSet())) }
                    LinkText("Clear") { update(q.copy(referenceHeroes=emptySet())) }
                }
                graph.scan.box?.allHeroes.orEmpty().filter { nameMatches(graph.heroes.heroName(it),name) }.sortedBy(graph.heroes::heroName).forEach { h->
                    val selected=h.id in q.referenceHeroes
                    Row(Modifier.fillMaxWidth().tap { update(q.copy(reference=SellReference.SELECTED,referenceHeroes=if(selected)q.referenceHeroes-h.id else q.referenceHeroes+h.id)) }.padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                        Face(graph.heroes.face(h),28.dp);Text(graph.heroes.heroName(h),style=zen(12.sp),modifier=Modifier.weight(1f));Checkbox(selected) { on->update(q.copy(reference=SellReference.SELECTED,referenceHeroes=if(on)q.referenceHeroes+h.id else q.referenceHeroes-h.id)) }
                    }
                }
            }
            SheetKind.SELL_INVENTORY->{
                SellToggle("In bag only",q.bagOnly) { update(q.copy(bagOnly=it)) }
                SellToggle("Include locked gear",q.includeLocked) { update(q.copy(includeLocked=it)) }
                SellToggle("Protect gear on pool heroes",q.protectPool) { update(q.copy(protectPool=it)) }
                EnhanceRange(q.minimumEnhance,q.maximumEnhance) { lo,hi->update(q.copy(minimumEnhance=lo,maximumEnhance=hi)) }
            }
            SheetKind.SELL_SLOTS->{
                OutlineButton("Any slot",Modifier.fillMaxWidth(),height=40.dp,enabled=q.slots.size<Slot.entries.size) { update(q.copy(slots=Slot.entries.toSet())) }
                CardGrid(Slot.entries,columns=3) { s,m->SellChoice(s.label,m,s in q.slots,leading={ GearIcon(s.icon,16.dp,C.ink) }) { update(q.copy(slots=if(s in q.slots)q.slots-s else q.slots+s)) } }
            }
            SheetKind.SELL_SETS->{
                var name by remember { mutableStateOf("") };NameFilter(name,"Name") { name=it }
                LinkText("Any set") { update(q.copy(sets=emptySet())) }
                CardGrid(graph.content.content.sets.filter { nameMatches(it.name,name) },columns=3) { s,m->
                    Column(m, horizontalAlignment=Alignment.CenterHorizontally) { SetIcon(s.icon,25.dp);SellChoice(s.name,Modifier.fillMaxWidth(),s.key in q.sets) { update(q.copy(sets=if(s.key in q.sets)q.sets-s.key else q.sets+s.key)) } }
                }
            }
            SheetKind.SELL_MAINS->{
                OutlineButton("Any main",Modifier.fillMaxWidth(),height=40.dp,enabled=q.mains.isNotEmpty()) { update(q.copy(mains=emptySet())) }
                CardGrid(GearStat.entries,columns=3) { s,m->SellChoice(s.short,m,s in q.mains,leading={ StatIcon(s.stat,14.dp,C.ink) }) { update(q.copy(mains=if(s in q.mains)q.mains-s else q.mains+s)) } }
            }
            SheetKind.SELL_LEVELS->{
                LinkText("Any level") { update(q.copy(levels=emptySet())) }
                CardGrid(graph.scan.box?.items.orEmpty().map { it.level }.distinct().sorted(),columns=4) { n,m->SellChoice("Lv.$n",m,n in q.levels) { update(q.copy(levels=if(n in q.levels)q.levels-n else q.levels+n)) } }
            }
            SheetKind.SELL_QUALITY->{
                Text("Choose overall roll quality. Starting values, upgrade hits and Heroic new-line rolls all count, using each stat’s own usual range.",style=zen(12.sp))
                CardGrid(listOf("Any" to null,"Low average" to 50.0,"Very low average" to 25.0,"Minimum only" to 0.0),columns=2) { (label,limit),m->SellChoice(label,m,q.maximumTotalQuality==limit) { update(q.copy(maximumTotalQuality=limit)) } }
                Text(rollLimitText(q),style=zen(12.sp,color=C.blue))
                Text("Low average: at or below the middle of each stat's range. For example, Speed +3 in 2–4 and HP% +6 in 4–8 are both midrange on Lv85 gear.",style=zen(11.sp,color=C.muted))
                SellNumber("Minimum upgrades recorded",q.minimumProcs.toDouble(),0.0,5.0,0) { update(q.copy(minimumProcs=(it?:2.0).toInt())) }
                if(q.maximumProcQuality!=null)Text("An additional upgrade-only limit is active: ${fmtNum(q.maximumProcQuality!!)}% of range.",style=zen(11.sp,color=C.blue))
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
                val row=graph.sellReview.result?.rows?.firstOrNull { it.item.id==sheet.heroId }
                if(row==null)Text("Update the search to see this piece.",style=zen(12.sp)) else {
                    SellCardHead(graph,row)
                    row.es?.let { es->
                        VSpace(4.dp)
                        Card(shadow=null,padding=PaddingValues(12.dp)) {
                            val open=es.finishShare>0&&es.bar!=null&&!es.reached&&es.supported
                            Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                val rare=open&&es.chance==0.0&&es.possible
                                Text(if(rare)"Rare" else if(open)chanceText(es.chance) else "ES ${es.now}",style=dela(28.sp,if(!open)C.ink else if(es.worthRolling(graph.sellReview.query.rollOn))C.blue else C.deepPink))
                                Text(when { es.bar==null->"no bar for this slot";rare->"only rare rolls reach ES ${es.bar}";open->"of finishes reach ES ${es.bar}";es.reached->"at or above your bar ${es.bar}";else->"finished · below bar ${es.bar}" },style=zen(12.sp,color=C.muted),modifier=Modifier.padding(bottom=5.dp))
                            }
                            VSpace(8.dp);EsBar(es);VSpace(3.dp);EsLegend(es)
                            if(es.finishShare>0) { VSpace(4.dp);Text("~${EsForecast.goldText(es.finishShare*EsForecast.FULL_GOLD)} gold to +15",style=zen(11.sp,color=C.muted)) }
                        }
                    }
                    SectionTitle("Rolls",trailing=row.rolls?.let { "${RollReview.overallWeakCount(it)} of ${it.size} low" })
                    RollTable(row.rolls)
                    var stats by remember(row.item.id) { mutableStateOf(false) }
                    Fold("Each stat at +15",stats,{ stats=!stats }) { row.lines.sortedBy { if(it.stat==GearStat.SPD)0 else 1 }.forEach { StatFinish(it,graph.sellReview.result!!.query.reforge) } }
                    if(row.safeguards.isNotEmpty()) { SectionTitle("Your stat safeguards");row.safeguards.forEach { SafeguardReason(it,it.active(row.keepChance)) } }
                    if(!graph.sellReview.fresh)Text("Inventory or settings changed; update before tracking.",style=zen(12.sp,color=C.deepPink))
                    if(graph.sellReview.result?.matches?.any { it.item.id==row.item.id }==true)OutlineButton("Track over game",Modifier.fillMaxWidth(),height=44.dp,enabled=graph.sellReview.fresh) { graph.trackSell(row.item.id) }
                    var combined by remember(row.item.id) { mutableStateOf(false) }
                    if(graph.sellReview.result!!.query.scoreFilter)Fold("Combined score",combined,{ combined=!combined }) {
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
