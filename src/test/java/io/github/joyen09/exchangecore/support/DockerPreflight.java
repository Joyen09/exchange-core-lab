package io.github.joyen09.exchangecore.support;

import org.testcontainers.DockerClientFactory;

/**
 * Answers "is Docker reachable?" once, before anything tries to start a container.
 *
 * <h2>Why this exists</h2>
 *
 * Without it, a dead Docker daemon produces about seventy failures — one per integration test — each
 * carrying a full {@code DockerClientProviderStrategy} stack trace, and none of them saying anything
 * about the code. The cost is not the failure, it is the five minutes of reading stack traces to
 * discover that none of them is about your change. That happened five times during Phase 3.
 *
 * <p>Testcontainers makes it worse by design: the first failed strategy lookup is cached with
 * <em>"Previous attempts to find a Docker environment failed. Will not retry"</em>, so every later test
 * reports a consequence of the first failure rather than its own.
 *
 * <p>This is the README's design principle applied to the development loop. The README already tells a
 * reader to check {@code docker info} before reading the stack traces; this makes checking unnecessary,
 * because whoever forgets is handed the answer instead. Don't remember to avoid the mistake — make it
 * impossible to be confused by.
 */
public final class DockerPreflight {

    /** Cached because the lookup is slow when it fails, which is exactly when it runs. */
    private static volatile Boolean dockerAvailable;

    private DockerPreflight() {}

    /**
     * @throws DockerUnavailableException before any container is started, if Docker is not reachable
     */
    public static void require() {
        if (dockerAvailable == null) {
            synchronized (DockerPreflight.class) {
                if (dockerAvailable == null) {
                    dockerAvailable = probe();
                }
            }
        }
        verify(dockerAvailable);
    }

    private static boolean probe() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Split from the probe so the message can be tested without a machine on which Docker is actually
     * down — which is not a state CI can be asked to produce on demand.
     */
    static void verify(boolean available) {
        if (!available) {
            throw new DockerUnavailableException(MESSAGE);
        }
    }

    static final String MESSAGE =
            """

            Docker is not reachable, so no integration test in this suite can run.

            This is an environment problem, not a test failure. Every test that needs PostgreSQL or
            Redpanda would otherwise fail with the same cause and its own stack trace — about seventy
            of them, none about your change.

            To fix it:
                start the Docker daemon
                docker info          # should print a server version
                ./gradlew test       # re-run

            If this keeps happening on a resource-constrained machine, see "Local development only"
            in the README's Known limitations. Restarting the daemon is the whole fix.
            """;

    /** Named so that it is obvious in a test report which kind of problem this is. */
    public static final class DockerUnavailableException extends IllegalStateException {
        DockerUnavailableException(String message) {
            super(message);
        }
    }
}
