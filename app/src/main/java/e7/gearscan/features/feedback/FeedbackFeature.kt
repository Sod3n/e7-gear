package e7.gearscan.features.feedback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.AppGraph
import e7.gearscan.state.Feature

class FeedbackFeature(g: AppGraph) : Feature(g, "feedback") {
    var lastError: String? by mutableStateOf(null)
}
