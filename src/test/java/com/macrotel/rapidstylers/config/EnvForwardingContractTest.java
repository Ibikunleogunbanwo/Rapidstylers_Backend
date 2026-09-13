package com.macrotel.rapidstylers.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the contract between the application's configuration surface and the
 * production compose file.
 *
 * <p>Why this exists: {@code docker-compose.prod.yml} passes only the variables its
 * {@code environment:} block names. A property the app reads but compose never
 * forwards therefore falls back silently to its built-in default — the operator
 * edits {@code .env}, nothing happens, and nothing anywhere reports a problem.
 * Fifteen variables were in exactly that state (CORS origins, cache warm-up, the
 * outbox tuning, the notification consumer group, and more). This test makes the
 * contract explicit so the same gap cannot quietly reopen.
 *
 * <p>Every variable the app reads must be in exactly one of two states.
 *
 * <ul>
 *   <li><b>Driven by .env</b> — compose assigns {@code VAR: ${VAR...}}, so the
 *       value in {@code .env} is authoritative.</li>
 *   <li><b>Container-fixed</b> — compose assigns a literal, which is a deliberate
 *       decision that {@code .env} does <em>not</em> control it. Each one must be
 *       declared in {@link #CONTAINER_FIXED}, with a reason, so the decision is
 *       recorded rather than merely implied.</li>
 * </ul>
 *
 * <p>A third state used to exist by accident — the variable absent from compose
 * entirely. That is what this test fails on.
 */
class EnvForwardingContractTest {

    /**
     * Variables the app reads that are deliberately given a literal by compose,
     * because inside the compose network the obvious {@code .env} value is wrong:
     * {@code .env.example} ships {@code localhost}, which in a container means the
     * container itself rather than the database, Redis or Kafka beside it.
     */
    private static final Map<String, String> CONTAINER_FIXED = Map.of(
            "DB_URL", "in-network DB host is the service name, not .env.example's localhost",
            "REDIS_HOST", "in-network service name, not .env.example's localhost",
            "REDIS_PORT", "in-network port, not the host-mapped one",
            "KAFKA_BOOTSTRAP_SERVERS", "in-network service name, not .env.example's localhost:9094");

    /**
     * Compose-supplied defaults that intentionally differ from the app's own. Kept
     * separate from {@link #CONTAINER_FIXED} because these are overridable from
     * {@code .env}; only the fallback differs.
     */
    private static final Map<String, String> INTENTIONAL_DEFAULT_OVERRIDES = Map.of(
            "RATE_LIMIT_TRUSTED_PROXIES",
            "compose supplies the Cloudflare ranges that production sits behind; the app's own default is empty so a local run trusts no proxy");

    private static final Path PROJECT_ROOT = Paths.get("").toAbsolutePath();
    private static final Path PROPERTIES = PROJECT_ROOT.resolve("src/main/resources/application.properties");
    private static final Path JAVA_SOURCES = PROJECT_ROOT.resolve("src/main/java");
    private static final Path COMPOSE = PROJECT_ROOT.resolve("docker-compose.prod.yml");

    /** Matches ${VAR} and ${VAR:default} — the only two shapes the app uses up. */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*)(?::([^}]*))?\\}");

    // ── Parsing ───────────────────────────────────────────────────────

    /**
     * The app's own default for each variable it reads, from properties and from
     * {@code @Value} annotations. Commented-out lines are skipped: a disabled
     * example is not a live configuration source. Where a variable appears twice,
     * the last definition wins, which is how Spring resolves a properties file.
     */
    private static Map<String, String> appDefaults() throws IOException {
        Map<String, String> defaults = new LinkedHashMap<>();
        List<Path> sources = new ArrayList<>();
        sources.add(PROPERTIES);
        try (Stream<Path> walk = Files.walk(JAVA_SOURCES)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
        }
        for (Path source : sources) {
            for (String line : Files.readAllLines(source)) {
                String trimmed = line.strip();
                if (trimmed.startsWith("#") || trimmed.startsWith("//")
                        || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                    continue;
                }
                Matcher m = PLACEHOLDER.matcher(line);
                while (m.find()) {
                    // group(2) is null for ${VAR} — no default at all.
                    defaults.put(m.group(1), m.group(2) == null ? "" : m.group(2));
                }
            }
        }
        return defaults;
    }

    /** The app service's {@code environment:} entries, as written. */
    private static Map<String, String> composeAppEnvironment() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        boolean inApp = false;
        boolean inEnvironment = false;
        for (String line : Files.readAllLines(COMPOSE)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.equals("app:")) {
                inApp = true;
                continue;
            }
            if (inApp && line.matches("^  \\S.*:.*") && !trimmed.equals("app:")) {
                inApp = false; // reached the next service
                inEnvironment = false;
            }
            if (inApp && trimmed.equals("environment:")) {
                inEnvironment = true;
                continue;
            }
            if (inApp && inEnvironment) {
                if (line.matches("^    \\S.*:.*") && !trimmed.equals("environment:")) {
                    inEnvironment = false; // left the environment block
                    continue;
                }
                Matcher m = Pattern.compile("^      ([A-Z_][A-Z0-9_]*):\\s*(.*)$").matcher(line);
                if (m.matches()) {
                    entries.put(m.group(1), m.group(2).strip());
                }
            }
        }
        return entries;
    }

    private static boolean isEnvDriven(String assigned) {
        return assigned.startsWith("${");
    }

    // ── The contract ──────────────────────────────────────────────────

    @Test
    void everyVariableTheAppReadsIsEitherEnvDrivenOrDeclaredContainerFixed() throws IOException {
        Map<String, String> defaults = appDefaults();
        Map<String, String> compose = composeAppEnvironment();

        List<String> absent = new ArrayList<>();
        List<String> frozen = new ArrayList<>();
        for (String variable : new TreeSet<>(defaults.keySet())) {
            String assigned = compose.get(variable);
            if (assigned == null) {
                absent.add(variable);
            } else if (!isEnvDriven(assigned) && !CONTAINER_FIXED.containsKey(variable)) {
                frozen.add(variable + " := " + assigned);
            }
        }

        assertTrue(absent.isEmpty(),
                "These variables are read by the app but docker-compose.prod.yml never forwards them, "
                        + "so editing them in .env silently does nothing and the app uses its built-in default:\n  "
                        + String.join("\n  ", absent)
                        + "\n\nFix: add each to the app service's environment: block as ${VAR:-<the app's own default>}."
                        + " Keeping the default identical means production behaviour does not change until someone"
                        + " sets the value.");

        assertTrue(frozen.isEmpty(),
                "These variables are given a literal by compose, so .env cannot affect them. If that is"
                        + " intentional, add them to CONTAINER_FIXED with a reason; otherwise make them"
                        + " ${VAR:-default}:\n  " + String.join("\n  ", frozen));
    }

    @Test
    void containerFixedDeclarationsAreStillAccurate() throws IOException {
        Map<String, String> compose = composeAppEnvironment();

        List<String> stale = new ArrayList<>();
        for (String declared : CONTAINER_FIXED.keySet()) {
            String assigned = compose.get(declared);
            if (assigned == null) {
                stale.add(declared + " is no longer assigned by compose at all");
            } else if (isEnvDriven(assigned)) {
                stale.add(declared + " is now driven by .env, so the CONTAINER_FIXED entry is obsolete");
            }
        }

        assertTrue(stale.isEmpty(),
                "The CONTAINER_FIXED list has drifted from the compose file — a stale entry would mask a real"
                        + " gap, so it must be kept honest:\n  " + String.join("\n  ", stale));
    }

    @Test
    void composeFallbacksMatchTheApplicationsOwnDefaults() throws IOException {
        Map<String, String> defaults = appDefaults();
        Map<String, String> compose = composeAppEnvironment();

        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, String> entry : compose.entrySet()) {
            String assigned = entry.getValue();
            Matcher m = Pattern.compile("^\\$\\{([A-Z_][A-Z0-9_]*):-(.*)}$").matcher(assigned);
            if (!m.matches()) {
                continue; // a hard requirement, or a literal — handled by the other tests
            }
            String variable = m.group(1);
            String composeDefault = m.group(2);
            if (!defaults.containsKey(variable)) {
                continue; // compose supplies something the app has no default for
            }
            if (INTENTIONAL_DEFAULT_OVERRIDES.containsKey(variable)) {
                continue;
            }
            String appDefault = defaults.get(variable);
            if (!appDefault.equals(composeDefault)) {
                mismatches.add(variable + ": compose would use '" + composeDefault
                        + "' but the app's own default is '" + appDefault + "'");
            }
        }

        assertTrue(mismatches.isEmpty(),
                "A compose fallback that differs from the app's own default silently changes behaviour when the"
                        + " variable is unset in .env. Make them identical, or record the difference in"
                        + " INTENTIONAL_DEFAULT_OVERRIDES:\n  " + String.join("\n  ", mismatches));
    }
}
