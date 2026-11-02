package e7.gearscan.features.records

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.state.FeatureContext
import e7.gearscan.features.scan.ScanFeature
import e7.gearscan.data.UpgradeRecord
import e7.gearscan.state.Feature

class RecordsFeature(ctx: FeatureContext, private val scan: ScanFeature) : Feature(ctx, "records") {
    var records: Map<Long, UpgradeRecord> by mutableStateOf(emptyMap())
        internal set

    val recordedSteps: Map<Long, UpgradeRecord> get() = records.filterValues { it.scan == scan.lastScan && it.history.size > 1 }
}
