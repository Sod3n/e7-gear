package e7.gearscan.state

import e7.gearscan.ui.Outcome

/** What a tool decided to track, or why it will not; the screen hands the plan to the tracker. */
sealed interface Plan<out T> {
    class Go<T>(val value: T) : Plan<T>

    class Stop(val outcome: Outcome) : Plan<Nothing>
}

inline fun <T> Plan<T>.then(next: (T) -> Outcome): Outcome =
    when (this) {
        is Plan.Go -> next(value)
        is Plan.Stop -> outcome
    }
