package com.macrotel.rapidstylers.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The production image replaces the JRE's tz rules with ones compiled from the
 * IANA source vendored in deploy/tzdata (see Dockerfile). This checks that build
 * input — not the JVM running the tests, whose rules are whatever the developer
 * has installed — so it never fails merely because a dev machine is out of date.
 */
class TimeZoneDataBuildTest {

    /** Oldest release with Alberta, BC, NWT and Manitoba on permanent daylight time. */
    private static final String MINIMUM = "2026e";

    private static final String[] SOURCE_FILES = {"africa", "antarctica", "asia", "australasia", "europe",
            "northamerica", "southamerica", "backward", "etcetera", "gmt", "jdk11_backward"};

    @Test
    void dockerfilePinsVendoredDataAtLeastTheRequiredRelease() throws IOException {
        String dockerfile = read(Paths.get("Dockerfile"));
        Matcher arg = Pattern.compile("(?m)^ARG TZDATA_VERSION=(\\S+)$").matcher(dockerfile);
        assertTrue(arg.find(), "Dockerfile must pin ARG TZDATA_VERSION");
        String version = arg.group(1);
        assertTrue(version.compareTo(MINIMUM) >= 0, "tz data " + version + " is older than " + MINIMUM);
        assertEquals("tzdata" + version, read(Paths.get("deploy/tzdata", version, "VERSION")).trim());
        for (String file : SOURCE_FILES) {
            assertTrue(Files.isRegularFile(Paths.get("deploy/tzdata", version, file)), "missing source " + file);
            assertTrue(dockerfile.contains(" " + file), "Dockerfile must compile " + file);
        }
        assertTrue(dockerfile.contains("${JAVA_HOME}/lib/tzdb.dat"), "runtime JRE's tzdb.dat must be replaced");
        assertTrue(dockerfile.contains("RuntimeTzCheck ${TZDATA_VERSION}"), "image build must verify the swap");
    }

    @Test
    void vendoredCompilerProducesRulesWithPermanentDaylightTimeInWesternCanada(@TempDir Path work)
            throws Exception {
        String dockerfile = read(Paths.get("Dockerfile"));
        Matcher arg = Pattern.compile("(?m)^ARG TZDATA_VERSION=(\\S+)$").matcher(dockerfile);
        assertTrue(arg.find());
        String version = arg.group(1);

        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertNotNull(javac, "tests need a JDK");
        Path classes = Files.createDirectories(work.resolve("classes"));
        List<String> javacArgs = new ArrayList<>(List.of("--release", "17", "-nowarn", "-d", classes.toString()));
        try (Stream<Path> sources = Files.list(Paths.get("deploy/tzdb-compiler/build/tools/tzdb"))) {
            javacArgs.addAll(sources.map(Path::toString).filter(s -> s.endsWith(".java")).sorted()
                    .collect(Collectors.toList()));
        }
        javacArgs.add(Paths.get("deploy/tzdb-compiler/RuntimeTzCheck.java").toString());
        assertEquals(0, javac.run(null, null, null, javacArgs.toArray(new String[0])), "vendored tool must compile");

        Path dat = work.resolve("tzdb.dat");
        List<String> compile = new ArrayList<>(List.of(java(), "-cp", classes.toString(),
                "build.tools.tzdb.TzdbZoneRulesCompiler",
                "-srcdir", Paths.get("deploy/tzdata", version).toString(), "-dstfile", dat.toString()));
        compile.addAll(List.of(SOURCE_FILES));
        run(compile);
        assertTrue(Files.size(dat) > 50_000, "tzdb.dat looks truncated");

        String check = run(List.of(java(), "-cp", classes.toString(), "build.tools.tzdb.TzdbCheck",
                dat.toString(), MINIMUM));
        assertTrue(check.contains("TZDB_CHECK_OK version=" + version), check);
    }

    @Test
    void startupLineNamesTheTzDataVersionAndBusinessZoneOffset() {
        String line = TimeZoneDataStartupLog.describe("America/Toronto", Instant.parse("2026-12-01T19:00:00Z"));
        assertTrue(line.startsWith("Time zone data: tzdb 20"), line);
        assertTrue(line.endsWith("app.time-zone=America/Toronto offset now -05:00"), line);
        assertTrue(TimeZoneDataStartupLog.describe("Not/AZone", Instant.EPOCH).endsWith("invalid zone"));
    }

    private static String java() {
        return Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String run(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "timed out: " + command);
        String text = new String(output, StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), text);
        return text;
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
