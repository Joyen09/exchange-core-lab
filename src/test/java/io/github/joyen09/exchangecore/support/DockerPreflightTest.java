package io.github.joyen09.exchangecore.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The preflight's message, tested without needing a machine on which Docker is down.
 *
 * <p>The probe and the verdict are separate methods for exactly this reason: "Docker is unavailable"
 * is not a state CI can be asked to produce on demand, and a check whose failure path has never been
 * executed is a check nobody has read the output of.
 */
class DockerPreflightTest {

    @Test
    @DisplayName("says what is wrong, that it is not the code, and what to do")
    void theMessageIsActionable() {
        assertThatThrownBy(() -> DockerPreflight.verify(false))
                .isInstanceOf(DockerPreflight.DockerUnavailableException.class)
                // The three things a reader needs, in the order they need them.
                .hasMessageContaining("Docker is not reachable")
                .hasMessageContaining("environment problem, not a test failure")
                .hasMessageContaining("docker info");
    }

    @Test
    @DisplayName("is silent when Docker is there")
    void availableDockerPassesQuietly() {
        assertThatCode(() -> DockerPreflight.verify(true)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("and the real probe agrees, since this suite is running")
    void theRealProbeSeesTheDaemonThisSuiteIsUsing() {
        // If this fails, the preflight is broken rather than Docker — every other integration test in
        // the run is proof the daemon is there.
        assertThatCode(DockerPreflight::require).doesNotThrowAnyException();
    }
}
