package e7.gearscan

import android.app.Application

class E7GearApp : Application() {
    val graph by lazy { AppGraph(this) }
}
