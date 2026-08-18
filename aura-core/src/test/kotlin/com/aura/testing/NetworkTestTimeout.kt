package com.aura.testing

import org.junit.rules.Timeout

/**
 * The class-level timeout every socket-backed test carries.
 *
 * CI hung for 40 minutes on 2026-08-13, and again after the fix, because the
 * fix was applied to the three classes a *count* identified. `ENGINEERING_HISTORY`
 * §3 had ruled `ProviderKeysTest` out as "fully bounded — 21 `runTest` blocks,
 * 21 explicit timeouts"; two of its tests were `runBlocking`, which carries no
 * timeout at all, and the hang was inside the class the count exonerated. It
 * passes locally every time — the starvation needs the two-core runner.
 *
 * So the rule is not "add a timeout where one looks missing". It is: **every
 * test class that can touch a socket gets this, whether or not its coroutine
 * builders look bounded.** Reasoning about which classes are safe is what
 * produced two hangs; a rule that applies uniformly cannot be reasoned wrong.
 *
 * A JUnit rule rather than converting callers to `runTest`: `runTest`
 * substitutes virtual time, and these tests await real work on
 * `Dispatchers.IO`. The rule interrupts the thread and names the test that
 * hung — which is the part that turns a 40-minute red X into a one-line fix.
 *
 * Class-level, so it also covers whatever test is added to the class next.
 *
 * 60 seconds: long enough that a cold, loaded two-core runner never trips it on
 * a healthy test, short enough that a hang costs a minute instead of the job's
 * whole ceiling.
 */
fun networkTestTimeout(): Timeout = Timeout.seconds(60)
