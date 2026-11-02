package e7.gearscan.features.records

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.AppGraph
import e7.gearscan.data.UpgradeRecord
import e7.gearscan.state.Feature

class RecordsFeature(g: AppGraph) : Feature(g, "records") {
    var records: Map<Long, UpgradeRecord> by mutableStateOf(emptyMap())
        internal set

    val recordedSteps: Map<Long, UpgradeRecord> get() = records.filterValues { it.scan == g.scan.lastScan && it.history.size > 1 }
}
