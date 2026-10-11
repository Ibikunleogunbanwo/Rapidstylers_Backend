package com.macrotel.rapidstylers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Keeps the walls between domains standing: a walled domain may use its own
 * repos, but reaches another domain's data only through that domain's
 * doorway service (for example booking's AppointmentRecords), never its repo.
 * Every domain is listed except config: its start-up seeders fill every
 * domain's tables with demo data, so they keep direct repo access.
 */
class DomainWallsTest {

    private static final Path SOURCES = Path.of("src/main/java/com/macrotel/rapidstylers");
    private static final Pattern REPO_IMPORT =
            Pattern.compile("import com\\.macrotel\\.rapidstylers\\.(\\w+)\\.(?:repo\\.)?(\\w+Repo);");

    @ParameterizedTest
    @ValueSource(strings = {"payment", "booking", "styler", "customer", "engagement", "media", "outbox",
            "auth", "admin", "catalog", "common", "security"})
    void domainUsesNoOtherDomainsRepos(String domain) throws IOException {
        List<String> breaches = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES.resolve(domain))) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                Matcher m = REPO_IMPORT.matcher(Files.readString(file));
                while (m.find()) {
                    if (!m.group(1).equals(domain)) breaches.add(file.getFileName() + " -> " + m.group(1) + "." + m.group(2));
                }
            }
        }
        assertEquals(List.of(), breaches, domain + " reaches past another domain's doorway");
    }
}
