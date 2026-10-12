package com.macrotel.rapidstylers.booking.service;

import com.macrotel.rapidstylers.styler.service.VendorZoneResolver;
import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.zone.ZoneRules;
import java.time.zone.ZoneRulesException;
import java.time.zone.ZoneRulesProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reminder's timing under the tz rules production actually runs: the
 * release vendored in deploy/tzdata and swapped into the image (2026e or
 * later, Alberta on permanent daylight time, -06:00 all year).
 *
 * <p>The JVM running the tests may carry older rules, so the check runs in a
 * child JVM whose java.time zone rules are replaced by a tzdb.dat compiled
 * from the vendored source — the same thing the Docker image does — and
 * calls the real BookingReminderPolicy and VendorZoneResolver there.
 */
public class BookingReminderTzdbTest {

    private static final String[] SOURCE_FILES = {"africa", "antarctica", "asia", "australasia", "europe",
            "northamerica", "southamerica", "backward", "etcetera", "gmt", "jdk11_backward"};

    @Test
    void albertaAppointmentOnNovemberSecondStartsAtMinusSixAndIsRemindedTwentyFourRealHoursBefore(
            @TempDir Path work) throws Exception {
        Path dat = compileVendoredTzdb(work);
        String out = run(List.of(java(),
                "--add-opens", "java.base/java.time.zone=ALL-UNNAMED",
                "-Djava.time.zone.DefaultZoneRulesProvider=" + VendoredRules.class.getName(),
                "-Drs.tzdb=" + dat,
                "-cp", System.getProperty("java.class.path"),
                Probe.class.getName()));
        Map<String, String> result = new HashMap<>();
        for (String line : out.split("\\R")) {
            int eq = line.indexOf('=');
            if (line.startsWith("probe.") && eq > 0) result.put(line.substring(6, eq), line.substring(eq + 1));
        }
        assertTrue(result.getOrDefault("version", "").compareTo("2026e") >= 0, out);
        assertEquals("America/Edmonton", result.get("zone"), out);
        assertEquals("-06:00", result.get("offset"), out);
        // 10:00 on 2026-11-02 in Alberta is 16:00Z (it would be 17:00Z under the old -07:00 winter rules).
        assertEquals("2026-11-02T16:00:00Z", result.get("start"), out);
        assertEquals("TOO_EARLY", result.get("at1559"), out);
        assertEquals("SEND", result.get("at1600"), out);
        assertEquals("SEND", result.get("at1630"), out);
        assertEquals("TOO_LATE", result.get("nov2at1430"), out);
        assertEquals("STARTED", result.get("nov2at1600"), out);
    }

    /** Runs in the child JVM, on the vendored rules. */
    public static final class Probe {
        public static void main(String[] args) {
            ZoneId zone = VendorZoneResolver.zoneForStyler(null, "Alberta");
            LocalDate day = LocalDate.of(2026, 11, 2);
            LocalTime ten = LocalTime.of(10, 0);
            Instant start = BookingReminderPolicy.startInstant(day, ten, zone);
            System.out.println("probe.version=" + ZoneRulesProvider.getVersions("America/Edmonton").lastKey());
            System.out.println("probe.zone=" + zone);
            System.out.println("probe.offset=" + zone.getRules().getOffset(start));
            System.out.println("probe.start=" + start);
            for (String[] at : new String[][]{{"at1559", "2026-11-01T15:59:00Z"}, {"at1600", "2026-11-01T16:00:00Z"},
                    {"at1630", "2026-11-01T16:30:00Z"}, {"nov2at1430", "2026-11-02T14:30:00Z"},
                    {"nov2at1600", "2026-11-02T16:00:00Z"}}) {
                System.out.println("probe." + at[0] + "=" + BookingReminderPolicy.decide("3", null, null, null,
                        day, ten, zone, Instant.parse(at[1]), 24, 2));
            }
        }
    }

    /**
     * Replaces the JDK's zone rules in the child JVM with a tzdb.dat file
     * (-Drs.tzdb), read the way the JDK reads its own.
     */
    public static final class VendoredRules extends ZoneRulesProvider {
        private final String version;
        private final Map<String, ZoneRules> rules = new HashMap<>();

        public VendoredRules() throws Exception {
            byte[] file = Files.readAllBytes(Paths.get(System.getProperty("rs.tzdb")));
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(file));
            if (in.readByte() != 1 || !"TZDB".equals(in.readUTF())) throw new IllegalStateException("not a TZDB file");
            String[] versions = new String[in.readShort()];
            for (int i = 0; i < versions.length; i++) versions[i] = in.readUTF();
            String[] regions = new String[in.readShort()];
            for (int i = 0; i < regions.length; i++) regions[i] = in.readUTF();
            byte[][] data = new byte[in.readShort()][];
            for (int i = 0; i < data.length; i++) {
                data[i] = new byte[in.readShort()];
                in.readFully(data[i]);
            }
            Method read = Class.forName("java.time.zone.Ser").getDeclaredMethod("read", DataInput.class);
            read.setAccessible(true);
            for (String ignored : versions) {
                int count = in.readShort();
                for (int i = 0; i < count; i++) {
                    String region = regions[in.readShort()];
                    byte[] bytes = data[in.readShort()];
                    rules.put(region, (ZoneRules) read.invoke(null, new DataInputStream(new ByteArrayInputStream(bytes))));
                }
            }
            version = versions[versions.length - 1];
        }

        @Override
        protected Set<String> provideZoneIds() {
            return new HashSet<>(rules.keySet());
        }

        @Override
        protected ZoneRules provideRules(String zoneId, boolean forCaching) {
            ZoneRules found = rules.get(zoneId);
            if (found == null) throw new ZoneRulesException("Unknown time-zone ID: " + zoneId);
            return found;
        }

        @Override
        protected NavigableMap<String, ZoneRules> provideVersions(String zoneId) {
            TreeMap<String, ZoneRules> map = new TreeMap<>();
            map.put(version, provideRules(zoneId, false));
            return map;
        }
    }

    /** Builds tzdb.dat from deploy/tzdata/<Dockerfile's TZDATA_VERSION> with the vendored OpenJDK compiler. */
    private static Path compileVendoredTzdb(Path work) throws Exception {
        String dockerfile = new String(Files.readAllBytes(Paths.get("Dockerfile")), StandardCharsets.UTF_8);
        Matcher arg = Pattern.compile("(?m)^ARG TZDATA_VERSION=(\\S+)$").matcher(dockerfile);
        assertTrue(arg.find(), "Dockerfile must pin ARG TZDATA_VERSION");
        String version = arg.group(1);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Path classes = Files.createDirectories(work.resolve("classes"));
        List<String> javacArgs = new ArrayList<>(List.of("--release", "17", "-nowarn", "-d", classes.toString()));
        try (Stream<Path> sources = Files.list(Paths.get("deploy/tzdb-compiler/build/tools/tzdb"))) {
            javacArgs.addAll(sources.map(Path::toString).filter(s -> s.endsWith(".java")).sorted()
                    .collect(Collectors.toList()));
        }
        assertEquals(0, javac.run(null, null, null, javacArgs.toArray(new String[0])), "vendored tool must compile");
        Path dat = work.resolve("tzdb.dat");
        List<String> compile = new ArrayList<>(List.of(java(), "-cp", classes.toString(),
                "build.tools.tzdb.TzdbZoneRulesCompiler",
                "-srcdir", Paths.get("deploy/tzdata", version).toString(), "-dstfile", dat.toString()));
        compile.addAll(List.of(SOURCE_FILES));
        run(compile);
        return dat;
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
}
