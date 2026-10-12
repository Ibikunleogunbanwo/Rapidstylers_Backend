/*
 * RapidStylers: run inside the runtime image after its lib/tzdb.dat has been
 * replaced. Fails the image build unless the JVM itself reports at least the
 * required tz data version and the expected Canadian offsets.
 * Usage: java RuntimeTzCheck.java <min-version, e.g. 2026e>   (or compiled, on a JRE)
 */
import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneRulesProvider;
import java.util.TimeZone;

public final class RuntimeTzCheck {

    /** zone id, instant, expected offset. Keep in step with build.tools.tzdb.TzdbCheck. */
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

    public static void main(String[] args) {
        String version = ZoneRulesProvider.getVersions("UTC").lastKey();
        if (args.length != 1 || version.compareTo(args[0]) < 0) {
            fail("JVM tz data " + version + " is older than required " + (args.length > 0 ? args[0] : "?"));
        }
        for (String[] row : EXPECTED) {
            Instant at = Instant.parse(row[1]);
            String actual = ZoneId.of(row[0]).getRules().getOffset(at).getId();
            if (!actual.equals(row[2])) {
                fail("java.time " + row[0] + " at " + row[1] + " is " + actual + ", expected " + row[2]);
            }
            // java.util.TimeZone reads the same file through a different code path.
            int legacySeconds = TimeZone.getTimeZone(row[0]).getOffset(at.toEpochMilli()) / 1000;
            int expectedSeconds = ZoneId.of(row[0]).getRules().getOffset(at).getTotalSeconds();
            if (legacySeconds != expectedSeconds) {
                fail("java.util.TimeZone " + row[0] + " at " + row[1] + " disagrees with java.time");
            }
        }
        System.out.println("RUNTIME_TZ_CHECK_OK version=" + version);
    }

    private static void fail(String message) {
        System.err.println("RUNTIME_TZ_CHECK_FAILED " + message);
        System.exit(1);
    }

    private RuntimeTzCheck() {
    }
}
