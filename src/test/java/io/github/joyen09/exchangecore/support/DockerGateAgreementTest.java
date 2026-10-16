package io.github.joyen09.exchangecore.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two gates decide whether the tests can run. This is what stops them drifting apart.
 *
 * <p>The build's {@code doFirst} stops before any test when Docker is unreachable, and
 * {@link DockerPreflight} stops before any container when Testcontainers cannot find a usable
 * environment. They are deliberately <em>not</em> the same question:
 *
 * <ul>
 *   <li>the build gate asks "does a daemon answer", cheaply, without a JVM, so that the common
 *       failure costs one message instead of seventy;
 *   <li>the preflight asks "can Testcontainers actually work", which is the question that decides
 *       whether a test will pass, and is the authoritative one.
 * </ul>
 *
 * <p>Different questions are fine. What is not fine is the cheap one quietly becoming <b>stricter</b>
 * than the authoritative one, because then the build refuses an environment the tests would have run
 * in — a gate that rejects valid work is worse than no gate. Someone adding a minimum version or
 * tightening a timeout on one side would do exactly that, and nothing would notice.
 *
 * <p>So the implication is asserted directly: <b>if Testcontainers says Docker is usable, the build
 * gate must agree.</b> The reverse is allowed — the daemon may answer while Testcontainers still
 * cannot find a strategy, and the preflight catching that is the whole reason it is kept.
 *
 * <p>This is the same remedy as {@code TradeIdBoundaryIT}: one rule stated in two places, with a test
 * holding them to each other rather than a comment asking them to agree.
 */
class DockerGateAgreementTest {

    private static final Path CRITERION = Path.of("scripts/docker-available.sh");

    @Test
    @DisplayName("the cheap gate is never stricter than the check it gates")
    void theBuildGateDoesNotRejectWhatTestcontainersAccepts() {
        boolean testcontainersSaysUsable = testcontainersAvailable();
        boolean buildGateSaysReachable = buildGateVerdict();

        if (testcontainersSaysUsable) {
            assertThat(buildGateSaysReachable)
                    .as("Testcontainers can run here, so scripts/docker-available.sh must not block "
                            + "the build — a gate stricter than the thing it gates refuses valid work")
                    .isTrue();
        }

        // The converse is deliberately unasserted: a daemon can answer while Testcontainers still
        // fails to find a usable strategy, and DockerPreflight exists to catch precisely that.
    }

    @Test
    @DisplayName("the criterion lives in one file, which both callers run")
    void thereIsExactlyOneDefinition() {
        // If this file disappears, somebody has inlined the check again — which is how the two gates
        // came to be separate statements of one rule in the first place.
        assertThat(Files.exists(CRITERION))
                .as("%s is the single definition read by the Gradle gate, the Makefile and this test",
                        CRITERION)
                .isTrue();
        assertThat(Files.isExecutable(CRITERION)).as("the Makefile invokes it directly").isTrue();
    }

    /** Runs the same script the build's gate runs, rather than a second copy of its logic. */
    private static boolean buildGateVerdict() {
        try {
            return new ProcessBuilder("sh", CRITERION.toString())
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start()
                            .waitFor()
                    == 0;
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean testcontainersAvailable() {
        try {
            return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
