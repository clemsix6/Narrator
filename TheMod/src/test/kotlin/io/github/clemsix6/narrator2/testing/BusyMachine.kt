package io.github.clemsix6.narrator2.testing

/**
 * What a busy test machine does to the wall clock (FLAKY-4), for the tests of the Forge's time limits. Measured with
 * 32 spinning threads on 16 cores, other agents' builds running: a caller that should have gone on at its deadline
 * went on up to 137 ms late (p99 76 ms) at normal priority; a module thread stopped by its meter took up to 7.5 ms
 * more to unwind; a call of a few microseconds took up to 4 ms. Two rules follow.
 *
 * - **A test whose subject is not time** runs its module with [TIME_SCALE] (`ModuleHost`), or a budget of
 *   [PATIENT_NANOS] (the meter, the gauge, the module threads, the rewriting's harness): only the limit it tests can
 *   stop it. Should that limit go, another one stops the module all the same, with another fault, and the test fails.
 * - **A test of time** keeps the game's budgets, and asserts what happened (abandoned or not, which thread, which
 *   fault) exactly; a bound it puts on the wall clock past a deadline allows [SLACK_MILLIS] more, far less than what
 *   the code would take without its limit (a module blocked for seconds, a JDK call of 75 ms or more).
 */
object BusyMachine {
    /**
     * How many times longer a `ModuleHost`'s own limits are in a test whose subject is not time: a call 10 s, `start`
     * 25 s, the margin and the gauge's clock 2.5 s (a saturated machine once held a call of 75 ms of work past 2 s).
     */
    const val TIME_SCALE = 500L

    /** A budget for code whose subject is not time, run by the meter, the gauge or the module threads directly. */
    const val PATIENT_NANOS = 10_000_000_000L

    /** What a busy machine may add to a thread's wait past a deadline: 3.6 times the worst lateness measured. */
    const val SLACK_MILLIS = 500L
    const val SLACK_NANOS = SLACK_MILLIS * 1_000_000
}
