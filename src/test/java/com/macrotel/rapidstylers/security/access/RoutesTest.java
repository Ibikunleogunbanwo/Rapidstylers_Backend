package com.macrotel.rapidstylers.security.access;

import org.junit.jupiter.api.Test;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the guest list honest against the controllers. Reads the controller
 * sources, like EnvForwardingContractTest reads the compose file.
 */
class RoutesTest {

    private static final Path CONTROLLERS = Path.of("src/main/java/com/macrotel/rapidstylers/controller");
    private static final Pattern CLASS_MAPPING = Pattern.compile("@RequestMapping\\(\"([^\"]+)\"\\)");
    private static final Pattern METHOD_MAPPING =
            Pattern.compile("@(?:Get|Post|Put|Delete|Patch)Mapping(?:\\((?:value\\s*=\\s*)?\"([^\"]*)\"\\))?");

    private static final Map<String, String[]> LISTS = Map.of(
            "PUBLIC", Routes.PUBLIC,
            "ADMIN", Routes.ADMIN,
            "STYLER", Routes.STYLER,
            "CUSTOMER", Routes.CUSTOMER,
            "UPLOAD", Routes.UPLOAD);

    @Test
    void everyListedRouteExistsInAController() throws IOException {
        Set<String> routes = new HashSet<>();
        for (ControllerRoute route : controllerRoutes()) {
            routes.add(route.path);
        }
        List<String> stale = new ArrayList<>();
        LISTS.forEach((name, paths) -> Arrays.stream(paths)
                .filter(path -> !routes.contains(path))
                .forEach(path -> stale.add(name + " " + path)));
        assertTrue(stale.isEmpty(), "Listed in Routes but no controller serves them (typo or deleted route): " + stale);
    }

    @Test
    void noRouteIsInTwoLists() {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        LISTS.values().forEach(paths -> Arrays.stream(paths)
                .filter(path -> !seen.add(path))
                .forEach(duplicates::add));
        assertTrue(duplicates.isEmpty(), "A route must have exactly one rule: " + duplicates);
    }

    @Test
    void publicRoutesNeverReadTheSignedInAccount() throws IOException {
        // A public route has no token to read, so an accountId read there is always null.
        AntPathMatcher matcher = new AntPathMatcher();
        List<String> wrong = new ArrayList<>();
        for (ControllerRoute route : controllerRoutes()) {
            boolean isPublic = Arrays.stream(Routes.PUBLIC).anyMatch(p -> matcher.match(p, route.path));
            if (isPublic && route.readsAccount) {
                wrong.add(route.path);
            }
        }
        assertTrue(wrong.isEmpty(), "These public routes read accountId, which is always null there: " + wrong);
    }

    private static List<ControllerRoute> controllerRoutes() throws IOException {
        List<ControllerRoute> routes = new ArrayList<>();
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
                    paths.add(prefix + (mapping.group(1) == null ? "" : mapping.group(1)));
                }
                for (int i = 0; i < paths.size(); i++) {
                    int to = (i + 1 < starts.size()) ? starts.get(i + 1) : source.length();
                    String method = source.substring(starts.get(i), to);
                    boolean readsAccount = method.contains("currentAccountId(")
                            || method.contains("requireAdmin(")
                            || method.contains("getAttribute(\"accountId\")");
                    routes.add(new ControllerRoute(paths.get(i), readsAccount));
                }
            }
        }
        return routes;
    }

    private static final class ControllerRoute {
        final String path;
        final boolean readsAccount;

        ControllerRoute(String path, boolean readsAccount) {
            this.path = path;
            this.readsAccount = readsAccount;
        }
    }
}
