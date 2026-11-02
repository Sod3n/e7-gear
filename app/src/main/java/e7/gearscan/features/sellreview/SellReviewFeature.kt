package e7.gearscan.features.sellreview

import e7.gearscan.AppGraph
import e7.gearscan.data.SellQuery
import e7.gearscan.state.Codecs
import e7.gearscan.state.Feature
import e7.gearscan.state.Weight

class SellReviewFeature(g: AppGraph) : Feature(g, "sellReview") {
    var query by saved("query", SellQuery(), Codecs.obj({ it.toJson() }, { SellQuery.of(it) }))
    val progress = savedFile("sell_progress.jsonl", backup = false, path = "sell_progress.jsonl")
    val run = work<Unit>("Sell review", Weight.Heavy, notice = "Checking sell filters")
    val curve = work<Unit>("Keep curve")
}
