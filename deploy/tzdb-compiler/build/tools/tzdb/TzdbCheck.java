/*
 * RapidStylers addition (not part of OpenJDK): reads a tzdb.dat written by
 * TzdbZoneRulesCompiler and fails unless it carries at least the required
 * tz data version and the Canadian offsets the business depends on.
 * Usage: java -cp <classes> build.tools.tzdb.TzdbCheck <tzdb.dat> <min-version, e.g. 2026e>
 */
package build.tools.tzdb;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class TzdbCheck {

    /** zone id, instant, expected offset. Keep in step with RuntimeTzCheck. */
    static final String[][] EXPECTED = {
            {"America/Edmonton", "2026-12-01T19:00:00Z", "-06:00"},
            {"America/Edmonton", "2026-07-01T18:00:00Z", "-06:00"},
            {"America/Yellowknife", "2026-12-01T19:00:00Z", "-06:00"},
            {"America/Inuvik", "2026-12-01T19:00:00Z", "-06:00"},
            {"America/Vancouver", "2026-12-01T19:00:00Z", "-07:00"},
            {"America/Winnipeg", "2026-12-01T19:00:00Z", "-05:00"},
            {"America/Regina", "2026-12-01T19:00:00Z", "-06:00"},
            {"America/Toronto", "2026-12-01T19:00:00Z", "-05:00"},
            {"America/Toronto", "2026-07-01T18:00:00Z", "-04:00"},
            {"America/Halifax", "2026-12-01T19:00:00Z", "-04:00"},
            {"America/St_Johns", "2026-12-01T19:00:00Z", "-03:30"},
            {"UTC", "2026-12-01T19:00:00Z", "Z"},
    };

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: TzdbCheck <tzdb.dat> <min-version>");
            System.exit(2);
        }
        byte[] file = Files.readAllBytes(Paths.get(args[0]));
        String minVersion = args[1];
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(file));
        if (in.readByte() != 1 || !"TZDB".equals(in.readUTF())) {
            fail("not a TZDB file: " + args[0]);
        }
        String[] versions = new String[in.readShort()];
        for (int i = 0; i < versions.length; i++) {
            versions[i] = in.readUTF();
        }
        String[] regions = new String[in.readShort()];
        for (int i = 0; i < regions.length; i++) {
            regions[i] = in.readUTF();
        }
        byte[][] rules = new byte[in.readShort()][];
        for (int i = 0; i < rules.length; i++) {
            rules[i] = new byte[in.readShort()];
            in.readFully(rules[i]);
        }
        Map<String, byte[]> byZone = new HashMap<>();
        for (int v = 0; v < versions.length; v++) {
            int count = in.readShort();
            for (int i = 0; i < count; i++) {
                byZone.put(regions[in.readShort()], rules[in.readShort()]);
            }
        }
        String version = versions[versions.length - 1];
        if (version.compareTo(minVersion) < 0) {
            fail("tz data " + version + " is older than required " + minVersion);
        }
        for (String[] row : EXPECTED) {
            byte[] bytes = byZone.get(row[0]);
            if (bytes == null) {
                fail("zone missing: " + row[0]);
            }
            ZoneRules zoneRules = (ZoneRules) Ser.read(new DataInputStream(new ByteArrayInputStream(bytes)));
            String actual = zoneRules.getOffset(Instant.parse(row[1])).getId();
            if (!actual.equals(row[2])) {
                fail(row[0] + " at " + row[1] + " is " + actual + ", expected " + row[2]);
            }
        }
        System.out.println("TZDB_CHECK_OK version=" + version + " zones=" + regions.length);
    }

    private static void fail(String message) {
        System.err.println("TZDB_CHECK_FAILED " + message);
        System.exit(1);
    }

    private TzdbCheck() {
    }
}
