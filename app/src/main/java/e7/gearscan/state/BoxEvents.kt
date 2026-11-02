package e7.gearscan.state

import e7.gearscan.data.Box

interface BoxEvents {
    fun boxReplacing()

    fun boxChanged(old: Box?, new: Box?)

    fun boxAdopted(box: Box?)

    fun imported()

    fun restored()

    fun erased()
}
