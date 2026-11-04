package e7.gearscan.features.sellreview

import androidx.compose.runtime.saveable.rememberSaveable
import e7.gearscan.value.Chance
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
fun AppGraph.trackMatches(id: Long? = null, openOverlay: Boolean = true): Outcome =
    sellReview.toTrack(id).then { (rows, query) -> tracker.starts.trackSellQueue(rows, query, openOverlay) }
private fun AppGraph.trackSell(id:Long?=null) = messages.show(trackMatches(id))
private fun AppGraph.markSell(id:Long,keep:Boolean) {
    val outcome=marks.markReviewed(id,keep)
    if(outcome is Outcome.Note)messages.showToast(outcome.text,"Undo") { marks.unmark(id) }
}

private fun openSell(m: AppGraph,k:SheetKind,id:Long=0) { m.nav.sheet=Sheet(k,id) }
private fun rollLimitText(q:SellQuery)=when(q.maximumTotalQuality) {
    null->"All overall roll values";0.0->"Only minimum rolls";25.0->"Average in bottom quarter";50.0->"Average at/below midrange";100.0->"Average up to usual maximum";else->"Average within lower ${fmtNum(q.maximumTotalQuality!!)}% of range"
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
    GearSearchIdentity(graph,row.item,showEs=false) { row.rolls?.let { Text("${RollReview.overallWeakCount(it)}/${it.size} low rolls",style=zen(10.sp,color=C.muted)) } }
}

@Composable
private fun OverallRollSummary(rolls:List<RollReview.Roll>?) {
    if(rolls==null)Text("Roll history unavailable",style=zen(12.sp,color=C.muted)) else {
        Text("${RollReview.overallWeakCount(rolls)} of ${rolls.size} rolls below midrange",style=zen(12.sp,color=C.blue))
        val starts=rolls.count { it.enhance==0 };val upgrades=rolls.count { it.upgrade };val reveals=rolls.count { it.reveal }
        Text("$starts starting + $upgrades upgrade${if(reveals>0)" + $reveals new-line" else ""} rolls",style=zen(11.sp,color=C.muted))
        RollReview.focus(rolls)?.let { focus->Text(claims("Most upgrade hits: ${focus.key.short} · ${focus.value} of $upgrades"),style=zen(11.sp)) }
    }
}

@Composable
private fun StatFinish(line:RollReview.Line,reforge:Boolean,compact:Boolean=false) {
    val s=line.stat
    val label="${s.short} ${s.format(line.current)}"
    if(line.maximum==null)Text(claims("$label · finish not forecast"),style=zen(11.sp,color=C.muted))
    else Text(claims(if(compact)"$label → up to ${s.format(line.maximum!!)}" else "$label now · ${s.format(line.minimum!!)}–${s.format(line.maximum!!)} at +15${if(reforge)" & eligible reforge" else ""}"),style=zen(11.sp,color=C.blue))
    if(!compact&&line.rare!=null&&line.maximum!=null&&line.rare!!>line.maximum!!)Text(claims("Rare-roll limit ${s.format(line.rare!!)} · odds unknown"),style=zen(10.sp,color=C.muted))
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
    val text=when { guard.now->"Already has $target";!guard.known->"$target: future unknown";guard.chance>0->"$target: ${Chance(guard.chance).text} chance";guard.possible->"$target: rare rolls could reach it; odds unknown";else->"$target: cannot reach in this forecast" }
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
    val reviewing = tab==1&&result!=null&&result.matches.any { it.item.id !in graph.marks.reviewed&&!graph.sellReview.keptByBar(it) }&&graph.sellReview.fresh
    // nothing left to review on a current list: no button that only re-runs the same search
    val doneReviewing = tab==1&&result!=null&&graph.sellReview.fresh&&!reviewing
    Frame(null,null,
        primary=when {
            graph.sellReview.busy -> null
            reviewing -> Action("Review over game",short="Over game") { graph.trackSell() }
            doneReviewing -> null
            else -> Action(if(result!=null&&!graph.sellReview.fresh)"Update matches" else "Find matches") { jump=true;graph.findSell() }
        },
        secondary=when {
            graph.sellReview.busy -> Secondary("Stop",Span.Fill) { graph.sellReview.stop() }
            reviewing||doneReviewing -> Secondary("Edit filters",Span.Fill,before=true) { tab=0 }
            else -> null
        }) {
        BackToPrevious();ScreenTitle("Sell review")
        Segmented(listOf("Set up","Matches"),tab) { tab=it }
        if(graph.sellReview.busy)Card(shadow=null,padding=PaddingValues(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) { Spinner();Text("Checking ${graph.sellReview.done} / ${graph.sellReview.total}",style=zen(13.sp)) }
        }
        if(tab==0) {
            RowCard("Stat safeguards",{openSell(graph,SheetKind.SELL_SAFEGUARDS)},labelWidth=112.dp) { Text(claims(when { q.keepStats.isEmpty()->"Off";q.keepStats==SellQuery.DEFAULT_GUARDS->"Default · ${q.keepStats.size} stats";else->"${q.keepStats.size} stats" }),style=zen(12.sp),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            RowCard("Gear to review",{openSell(graph,SheetKind.SELL_INVENTORY)},labelWidth=112.dp) { Text(claims("${if(q.bagOnly)"Bag" else "All gear"} · +${q.minimumEnhance/3*3} to +${if(q.maximumEnhance>=15)15 else q.maximumEnhance/3*3}"),style=zen(12.sp),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            RowCard("Slots",{openSell(graph,SheetKind.SELL_SLOTS)},labelWidth=112.dp) { Text(if(q.slots.size==6)"Any slot" else q.slots.joinToString { it.label },style=zen(12.sp),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            RowCard("Sets",{openSell(graph,SheetKind.SELL_SETS)},labelWidth=112.dp) { Text(if(q.sets.isEmpty())"Any set" else "${q.sets.size} selected",style=zen(12.sp),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            RowCard("Main stats",{openSell(graph,SheetKind.SELL_MAINS)},labelWidth=112.dp) { Text(if(q.mains.isEmpty())"Any main" else q.mains.joinToString { it.short },style=zen(12.sp),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            //levels filter removed (user 2026-11-01: useless in the current game)
            RowCard("More filters",{openSell(graph,SheetKind.SELL_EXTRA)},labelWidth=112.dp) { Text(claims(listOfNotNull("Combined score".takeIf { q.scoreFilter },"No forecast shown".takeIf { q.includeUnknown },"Lv85 crafted only".takeIf { q.standardOnly }).joinToString(" · ").ifEmpty { "Off" }),style=zen(12.sp),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            RowCard("Kept pieces",{graph.nav.go(Screen.SellKept)},labelWidth=112.dp) { Text("${graph.marks.kept.size} kept",style=zen(12.sp),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            SectionTitle("Final step")
            Box(Modifier.fillMaxWidth().hardShadow(C.pink,4.dp,4.dp,14.dp)) {
                RowCard("Worth rolling",{openSell(graph,SheetKind.SELL_WORTH)},labelWidth=112.dp) { Text(claims("${Chance(q.rollOn/100).text}+ to reach ${(q.esBar?:graph.sellReview.autoEsBar())?.let { "ES $it" }?:"your ES bar"}${if(q.esBars.isNotEmpty())" · per slot" else ""}"),style=zen(12.sp),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
        }else if(result==null)Card {
            Text("Tap Find matches",style=dela(17.sp))
        }else {
            val done=graph.marks.reviewed;val grouped=graph.sellReview.grouped
            // ordered over the whole result, so marking a piece never reorders the groups left
            val matches=remember(result,grouped,graph.scan.box) { graph.sellReview.ordered(result.matches) { it.item } }
            val rows=remember(result,grouped,graph.scan.box) { graph.sellReview.ordered(result.rows) { it.item } }
            val open=matches.filter { it.item.id !in done }
            val byBar=open.filter { graph.sellReview.keptByBar(it) };val pending=open.filter { !graph.sellReview.keptByBar(it) }
            val guarded=rows.filter { it.protected&&it.item.id !in done };val unknown=rows.filter { it.unknown&&!it.protected&&it.item.id !in done }
            Card(shadow=null,padding=PaddingValues(12.dp)) {
                Text("${pending.size} pieces to review",style=dela(19.sp))
                if(result.query.minimumProcs>0||result.query.maximumTotalQuality!=null)Text(claims("${if(result.query.minimumProcs>0)"${result.query.minimumProcs}+ upgrades · " else ""}${rollLimitText(result.query)}"),style=zen(11.sp,color=C.blue))
                if(guarded.isNotEmpty()||byBar.isNotEmpty()||unknown.isNotEmpty()) {
                    VSpace(8.dp)
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        if(guarded.isNotEmpty())Pill("${guarded.size} kept by safeguards",selected=category==1,onClick={ category=if(category==1)0 else 1;count=20 })
                        if(byBar.isNotEmpty())Pill("${byBar.size} kept by ES bar",selected=category==3,onClick={ category=if(category==3)0 else 3;count=20 })
                        if(unknown.isNotEmpty())Pill("${unknown.size} no forecast",selected=category==2,onClick={ category=if(category==2)0 else 2;count=20 })
                    }
                }
            }
            if(!graph.sellReview.fresh&&!graph.sellReview.busy)Text("Inventory or settings changed. Update before starting a list.",style=zen(12.sp,color=C.deepPink))
            val shown=when(category) { 1->guarded;2->unknown;3->byBar;else->pending }
            // a pill whose list emptied (a setting changed) gives way to the review list
            if(category!=0&&shown.isEmpty())LaunchedEffect(category) { category=0 }
            if(shown.isEmpty())Card {
                Text("Nothing matches",style=dela(17.sp))
            }else Segmented(listOf("Worst rolls","By set"),if(grouped)1 else 0) { graph.sellReview.grouped=it==1 }
            GroupedRows(graph,grouped,shown.take(count),shown,{ it.item }) { row->Card(shadow=null,radius=14.dp,border=2.dp,padding=PaddingValues(12.dp)) {
                Column(Modifier.tap { openSell(graph,SheetKind.SELL_DETAILS,row.item.id) }) {
                    SellCardHead(graph,row)
                    VSpace(10.dp)
                    row.es?.let { es->
                        Row(verticalAlignment=Alignment.Top) {
                            Text("ES ${es.now}",style=dela(17.sp),modifier=Modifier.weight(1f))
                            EsChip(es,q.rollOn)
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
@OptIn(LinkStyle::class, KeyboardInput::class)
fun SellSheet(graph: AppGraph,sheet:Sheet) {
    val q=graph.sellReview.query
    fun update(v:SellQuery)=graph.sellReview.edit(v)
    val title=when(sheet.kind) {
        SheetKind.SELL_INVENTORY->"Gear to review";SheetKind.SELL_SLOTS->"Slots";SheetKind.SELL_SETS->"Sets";SheetKind.SELL_MAINS->"Main stats"
        SheetKind.SELL_DETAILS->"Rolls & stat potential";SheetKind.SELL_SAFEGUARDS->"Stat safeguards";SheetKind.SELL_EXTRA->"More filters";SheetKind.SELL_WORTH->"When to keep rolling";else->"How gear is compared"
    }
    key(sheet.kind) {
    SheetBody(title,button="Done",onButton={ graph.nav.sheet=null }) {
        when(sheet.kind) {
            SheetKind.SELL_EXTRA->{
                SellToggle("Show pieces without a forecast",q.includeUnknown) { update(q.copy(includeUnknown=it)) }
                SellToggle("Lv85 crafted gear only",q.standardOnly) { update(q.copy(standardOnly=it)) }
                OutlineButton("Reset all Sell review settings",Modifier.fillMaxWidth(),height=40.dp) { graph.messages.askReset("Reset Sell review?","Every Sell review setting goes back to its default. Sold and kept marks stay.",graph.sellReview::resetSettings) }
            }
            SheetKind.SELL_WORTH->{
                KeepRollingSlider(q.rollOn,graph.sellReview.keepCurve,graph.sellReview.curveBusy||graph.sellReview.busy,{ graph.findSell() },{ t,b->graph.messages.explain(t,b) }) { update(q.copy(rollOn=it)) }
                SellToggle("Count reforging",q.reforge) { update(q.copy(reforge=it)) }
                SectionTitle("ES bar")
                val auto=graph.sellReview.autoEsBar()
                ValueSlider("All slots",{},(q.esBar?:auto?:80).toDouble(),EsForecast.esRange.first.toDouble(),EsForecast.esRange.last.toDouble(),1.0,{ if(q.esBar==null&&auto==null)"none" else "${it.toInt()}" }) { v->
                    update(q.copy(esBar=v.toInt())) }
                OutlineButton("Reset to your +15 median",Modifier.fillMaxWidth(),height=40.dp,enabled=q.esBar!=null) { graph.messages.askReset("Reset the ES bar?","It goes back to the middle ES of your own +15 pieces.",graph.sellReview::resetEsBar) }
                // per-slot bars are the exception: folded under the one bar, open when any slot has its own
                var perSlot by rememberSaveable { mutableStateOf(q.esBars.isNotEmpty()) }
                Fold(if(q.esBars.isEmpty())"Per slot" else "Per slot · ${q.esBars.size} of 6",perSlot,{ perSlot=!perSlot }) {
                val one=q.esBar?:auto
                Slot.entries.forEach { slot->ValueSlider(slot.label,{ GearIcon(slot.icon,18.dp,C.ink) },(q.esBars[slot]?:one?:80).toDouble(),EsForecast.esRange.first.toDouble(),EsForecast.esRange.last.toDouble(),1.0,{ if(q.esBars[slot]==null&&one==null)"none" else "${it.toInt()}" }) { v->
                    update(q.copy(esBars=q.esBars+(slot to v.toInt()))) } }
                OutlineButton("Reset all to one bar",Modifier.fillMaxWidth(),height=40.dp,enabled=q.esBars.isNotEmpty()) { graph.messages.askReset("Use one bar again?","Every slot goes back to the one ES bar above.",graph.sellReview::resetSlotBars) }
                }
            }
            SheetKind.SELL_SAFEGUARDS->{
                Text("Pieces with a ${Chance(q.keepChance/100).text}+ chance to reach one of these stay out of Sell review.",style=zen(13.sp))
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
                OutlineButton("Any set",Modifier.fillMaxWidth(),height=40.dp,enabled=q.sets.isNotEmpty()) { update(q.copy(sets=emptySet())) }
                CardGrid(graph.content.content.sets.filter { nameMatches(it.name,name) },columns=3) { s,m->
                    Column(m, horizontalAlignment=Alignment.CenterHorizontally) { SetIcon(s.icon,25.dp);SellChoice(s.name,Modifier.fillMaxWidth(),s.key in q.sets) { update(q.copy(sets=if(s.key in q.sets)q.sets-s.key else q.sets+s.key)) } }
                }
            }
            SheetKind.SELL_MAINS->{
                OutlineButton("Any main",Modifier.fillMaxWidth(),height=40.dp,enabled=q.mains.isNotEmpty()) { update(q.copy(mains=emptySet())) }
                CardGrid(GearStat.entries,columns=3) { s,m->SellChoice(s.short,m,s in q.mains,leading={ StatIcon(s.stat,14.dp,C.ink) }) { update(q.copy(mains=if(s in q.mains)q.mains-s else q.mains+s)) } }
            }
            SheetKind.SELL_DETAILS->{
                val row=graph.sellReview.result?.rows?.firstOrNull { it.item.id==sheet.heroId }
                if(row==null)Text("Update the search to see this piece.",style=zen(12.sp)) else {
                    SellCardHead(graph,row)
                    row.es?.let { es->
                        VSpace(4.dp)
                        Card(shadow=null,padding=PaddingValues(12.dp)) {
                            val reforge=es.finishShare==0.0&&(es.best?:0.0)>es.now
                            val open=(es.finishShare>0||reforge)&&es.bar!=null&&!es.reached&&es.supported
                            Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                val sell=esKeeps(es)==false&&es.best!=null
                                Text(if(sell)"Max ${kotlin.math.floor(es.best!!).toInt()}" else if(open)Chance(es.chance).text else "ES ${es.now}",style=dela(28.sp,if(!open&&!sell)C.ink else esTint(es,graph.sellReview.query.rollOn)))
                                Text(claims(when { es.bar==null->"no bar for this slot";sell->"your bar ${es.bar}";open&&reforge->"reach ES ${es.bar} after reforge";open->"of finishes reach ES ${es.bar}";es.reached->"at or above your bar ${es.bar}";else->"finished · below bar ${es.bar}" }),style=zen(12.sp,color=C.muted),modifier=Modifier.padding(bottom=5.dp))
                            }
                            VSpace(8.dp);EsBar(es)
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
