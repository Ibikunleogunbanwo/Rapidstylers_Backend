package com.macrotel.rapidstylers.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards a whole class of bug: a controller method reads the {@code accountId}
 * request attribute, but only {@link JwtAuthFilter} sets it, and only for paths
 * listed in its ROLE_PATHS. A route missing from that list always answers 401
 * (this is how /styler/reviews and /auth/logout broke). Reads the controller
 * sources, like EnvForwardingContractTest reads the compose file.
 */
class AccountRoutesAreProtectedTest {

    private static final Path CONTROLLERS = Path.of("src/main/java/com/macrotel/rapidstylers/controller");
    private static final Pattern CLASS_MAPPING = Pattern.compile("@RequestMapping\\(\"([^\"]+)\"\\)");
    private static final Pattern METHOD_MAPPING =
            Pattern.compile("@(?:Get|Post|Put|Delete|Patch)Mapping\\((?:value\\s*=\\s*)?\"([^\"]+)\"");

    @Test
    void everyRouteThatReadsTheAccountIsListedInTheJwtFilter() throws IOException {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.list(CONTROLLERS)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String source = Files.readString(file);
                Matcher base = CLASS_MAPPING.matcher(source);
                String prefix = base.find() ? base.group(1) : "";

                Matcher mapping = METHOD_MAPPING.matcher(source);
                List<Integer> starts = new ArrayList<>();
                List<String> paths = new ArrayList<>();
                while (mapping.find()) {
                    starts.add(mapping.start());
                    paths.add(prefix + mapping.group(1));
                }
                for (int i = 0; i < paths.size(); i++) {
                    int from = starts.get(i);
                    int to = (i + 1 < starts.size()) ? starts.get(i + 1) : source.length();
                    String method = source.substring(from, to);
                    boolean readsAccount = method.contains("currentAccountId(")
                            || method.contains("getAttribute(\"accountId\")");
                    if (readsAccount && rolesFor(paths.get(i)) == null) {
                        missing.add(file.getFileName() + " " + paths.get(i));
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(),
                "These routes read accountId but are not in JwtAuthFilter.ROLE_PATHS, so they always return 401: "
                        + missing);
    }

    private static Object rolesFor(String path) {
        return ReflectionTestUtils.invokeMethod(JwtAuthFilter.class, "requiredRolesFor", path);
    }
}
