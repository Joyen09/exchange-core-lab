#!/bin/sh
# The one definition of "is Docker reachable enough to start a container".
#
# Three callers read this file rather than each deciding for themselves: the Gradle test task's
# preflight, the Makefile's test target, and DockerGateAgreementTest. The rule was in two places
# before, and two statements of one rule drift — the same lesson as CHECK (trade_id >= 0) against
# TradeSnapshot's constructor, which TradeIdBoundaryIT now binds together.
#
# This is deliberately the *cheap* check, and deliberately weaker than the authoritative one.
# DockerPreflight asks Testcontainers whether it can actually find a usable strategy, which is the
# question that matters once a test is running. This one asks whether a daemon answers at all, in a
# tenth of the time and without a JVM, so the common failure stops the build before anything starts.
#
# The relationship between them is the thing that must not break: this check must never be *stricter*
# than Testcontainers' — a gate that rejects an environment the tests would have run in is worse than
# no gate. DockerGateAgreementTest asserts exactly that implication.
#
# Exit 0 when a daemon answers, non-zero otherwise.
exec docker info >/dev/null 2>&1
