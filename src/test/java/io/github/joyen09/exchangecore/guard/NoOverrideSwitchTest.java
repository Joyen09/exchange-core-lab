package io.github.joyen09.exchangecore.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A repository-level guard for the isolation rules that no unit test can express, because they are
 * about what must <em>not</em> exist anywhere in the tree (SPEC §1.1, §1.3, §1.4).
 *
 * <p>Two rule sets with deliberately different scopes:
 *
 * <ul>
 *   <li><b>Mainnet hosts</b> — checked over shipped source and configuration only. Documentation
 *       ({@code docs/}, {@code SPEC.md}, {@code README.md}) has to name mainnet endpoints to explain
 *       why they are blocked, and {@code src/test} uses them as negative fixtures. Flagging those
 *       would be a false positive, so they are out of scope for this rule.
 *   <li><b>Back doors and foreign credentials</b> — checked over the entire tree including tests,
 *       because there is no legitimate reason for an override flag, another project's name, or a
 *       private key block to appear anywhere.
 * </ul>
 *
 * <p>This file excludes itself from both scans: it is the only place the forbidden literals may
 * legitimately appear, since it is the thing looking for them.
 */
class NoOverrideSwitchTest {

    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath();

    private static final String SELF = "NoOverrideSwitchTest.java";

    /** Directories that never contain deliverable source. */
    private static final Set<String> IGNORED_DIRECTORIES =
            Set.of(".git", ".gradle", "build", "out", ".idea", ".kotlin", "node_modules");

    /** Documentation is exempt from the mainnet-host rule: explaining the ban requires naming it. */
    private static final Set<String> DOCUMENTATION = Set.of("SPEC.md", "README.md");

    private static final Set<String> BINARY_EXTENSIONS =
            Set.of(".jar", ".class", ".png", ".jpg", ".jpeg", ".gif", ".zip", ".gz", ".p12", ".jks", ".ico");

    private static final List<Pattern> MAINNET_HOSTS = List.of(
            Pattern.compile("\\bapi\\d*\\.binance\\.com\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b[fd]api\\.binance\\.com\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bstream\\.binance\\.com\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdata-api\\.binance\\.vision\\b", Pattern.CASE_INSENSITIVE));

    private static final List<Pattern> BACK_DOORS = List.of(
            Pattern.compile("allow[_-]?mainnet", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(disable|skip|bypass)[_-]?(endpoint[_-]?)?guard", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(disable|skip|bypass)[_-]?allowlist", Pattern.CASE_INSENSITIVE));

    private static final List<Pattern> FOREIGN_ARTEFACTS = List.of(
            // The author's live trading bot. Nothing from it may be referenced here (SPEC §1.1).
            Pattern.compile("pionex", Pattern.CASE_INSENSITIVE),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"));

    @Test
    @DisplayName("no mainnet endpoint appears in shipped source or configuration")
    void noMainnetEndpointsInSourceOrConfig() {
        assertThat(findMatches(this::isShippedSourceOrConfig, MAINNET_HOSTS))
                .as("SPEC §1.4: only Binance Spot Testnet endpoints may appear in source or configuration")
                .isEmpty();
    }

    @Test
    @DisplayName("no override switch exists anywhere in the repository")
    void noOverrideSwitchAnywhere() {
        assertThat(findMatches(path -> true, BACK_DOORS))
                .as("SPEC §1.4: the allowlist must have no escape hatch, not even an unused one")
                .isEmpty();
    }

    @Test
    @DisplayName("no reference to the author's live trading project, and no private key material")
    void noForeignProjectReferencesOrKeys() {
        assertThat(findMatches(path -> true, FOREIGN_ARTEFACTS))
                .as("SPEC §1.1 and §1.3: this repository is independent of any live system")
                .isEmpty();
    }

    @Test
    @DisplayName("the example environment file holds placeholders only")
    void environmentTemplateHoldsPlaceholdersOnly() {
        String content = read(REPO_ROOT.resolve(".env.example"));
        List<String> suspicious = content
                .lines()
                .filter(line -> line.contains("=") && !line.trim().startsWith("#"))
                .filter(line -> {
                    String value = line.substring(line.indexOf('=') + 1).trim();
                    return !value.toUpperCase(Locale.ROOT).startsWith("REPLACE_WITH_");
                })
                .toList();
        assertThat(suspicious)
                .as("SPEC §1.3: .env.example must contain placeholders, never key-shaped values")
                .isEmpty();
    }

    @Test
    @DisplayName("the scanner actually reads the repository it is meant to protect")
    void scannerSeesTheRepository() {
        // Without this, a broken path assumption would turn every assertion above into a
        // vacuously passing test.
        assertThat(REPO_ROOT.resolve("build.gradle.kts")).exists();
        assertThat(scannableFiles(this::isShippedSourceOrConfig))
                .as("mainnet scan must cover the main source set and the compose file")
                .anyMatch(path -> path.endsWith("ExchangeEndpointAllowlist.java"))
                .anyMatch(path -> path.endsWith("docker-compose.yml"))
                .hasSizeGreaterThan(10);
    }

    private boolean isShippedSourceOrConfig(Path relative) {
        if (DOCUMENTATION.contains(relative.getFileName().toString())) {
            return false;
        }
        return !relative.startsWith(Paths.get("src", "test"));
    }

    private List<String> findMatches(java.util.function.Predicate<Path> scope, List<Pattern> patterns) {
        List<String> hits = new ArrayList<>();
        for (Path relative : scannableFiles(scope)) {
            String content = read(REPO_ROOT.resolve(relative));
            for (Pattern pattern : patterns) {
                var matcher = pattern.matcher(content);
                if (matcher.find()) {
                    hits.add("%s: matched /%s/ ('%s')".formatted(relative, pattern.pattern(), matcher.group()));
                }
            }
        }
        return hits;
    }

    private List<Path> scannableFiles(java.util.function.Predicate<Path> scope) {
        try (Stream<Path> walk = Files.walk(REPO_ROOT)) {
            return walk.filter(Files::isRegularFile)
                    .map(REPO_ROOT::relativize)
                    .filter(NoOverrideSwitchTest::isNotGloballyExcluded)
                    .filter(NoOverrideSwitchTest::isNotInIgnoredDirectory)
                    .filter(NoOverrideSwitchTest::isTextFile)
                    .filter(scope)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to walk repository at " + REPO_ROOT, e);
        }
    }

    /**
     * Excluded from every rule: this scanner itself, the specification, and the ADRs — all of which
     * quote the forbidden strings in order to explain them.
     */
    private static boolean isNotGloballyExcluded(Path relative) {
        return !relative.getFileName().toString().equals(SELF)
                && !relative.equals(Paths.get("SPEC.md"))
                && !relative.startsWith(Paths.get("docs"));
    }

    private static boolean isNotInIgnoredDirectory(Path relative) {
        for (Path segment : relative) {
            if (IGNORED_DIRECTORIES.contains(segment.toString())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTextFile(Path relative) {
        String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
        return BINARY_EXTENSIONS.stream().noneMatch(name::endsWith);
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + file, e);
        }
    }
}
