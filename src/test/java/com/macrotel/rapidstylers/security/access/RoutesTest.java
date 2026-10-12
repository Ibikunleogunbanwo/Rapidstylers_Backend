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

    private static final Path SOURCES = Path.of("src/main/java/com/macrotel/rapidstylers");
    private static final Pattern CLASS_MAPPING = Pattern.compile("@RequestMapping\\(\"([^\"]+)\"\\)");
    private static final Pattern METHOD_MAPPING =
            Pattern.compile("@(?:Get|Post|Put|Delete|Patch)Mapping(?:\\((?:value\\s*=\\s*)?\"([^\"]*)\"[^)]*\\))?");

    private static final Pattern PRE_AUTHORIZE =
            Pattern.compile("@(?:Get|Post|Put|Delete|Patch)Mapping(?:\\([^)]*\\))?\\s*@PreAuthorize\\(\"([^\"]+)\"\\)");

    private static final Map<String, String[]> LISTS = Map.of(
            "PUBLIC", Routes.PUBLIC,
            "ADMIN", Routes.ADMIN,
            "STAFF", Routes.STAFF,
            "STYLER", Routes.STYLER,
            "CUSTOMER", Routes.CUSTOMER,
            "SUPPORT", Routes.SUPPORT,
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

    /**
     * The route rule and a method's @PreAuthorize must agree: a staff route whose
     * method still says hasRole('ADMIN') would refuse managers the list allows,
     * and an owner-only route whose method lets MANAGER in would be one list edit
     * away from opening money or settings to managers.
     */
    @Test
    void preAuthorizeAgreesWithTheRouteLists() throws IOException {
        List<String> wrong = new ArrayList<>();
        for (ControllerRoute route : controllerRoutes()) {
            if (route.preAuthorize == null) continue;
            boolean letsManagerIn = route.preAuthorize.contains("MANAGER");
            if (Arrays.asList(Routes.STAFF).contains(route.path) && !letsManagerIn) {
                wrong.add("STAFF route refuses managers: " + route.path);
            }
            if (Arrays.asList(Routes.ADMIN).contains(route.path) && letsManagerIn) {
                wrong.add("Owner-only route lets managers in: " + route.path);
            }
        }
        assertTrue(wrong.isEmpty(), String.valueOf(wrong));
    }

    /** Owner-only work never moves to the staff list by accident. */
    @Test
    void moneyExportsAndAccountsStayOwnerOnly() {
        List<String> leaked = new ArrayList<>();
        for (String path : Routes.STAFF) {
            if (path.matches(".*(refund|dispute|payout|pay_|recovery|reconciliation|revenue|commission|settings"
                    + "|export|announcement|newsletter|site_content|seo|blog|_service|identification$|promo|service_area"
                    + "|funnel|audit|suspend|reinstate|accounts|decrypt|business_summaries|connect_statuses).*")
                    && !path.endsWith("/admin/identification_image")) {
                leaked.add(path);
            }
        }
        assertTrue(leaked.isEmpty(), "Owner-only work listed as STAFF: " + leaked);
    }

    private static List<ControllerRoute> controllerRoutes() throws IOException {
        List<ControllerRoute> routes = new ArrayList<>();
        // Each domain has its own controller folder, so read every *Controller.java.
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith("Controller.java"))::iterator) {
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
                            || method.contains("CurrentAccount.id(")
                            || method.contains("requireAdmin(")
                            || method.contains("getAttribute(\"accountId\")");
                    // The @PreAuthorize right after the mapping, if any.
                    Matcher pre = PRE_AUTHORIZE.matcher(method);
                    String preAuthorize = pre.lookingAt() ? pre.group(1) : null;
                    routes.add(new ControllerRoute(paths.get(i), readsAccount, preAuthorize));
                }
            }
        }
        return routes;
    }

    private static final class ControllerRoute {
        final String path;
        final boolean readsAccount;
        final String preAuthorize;

        ControllerRoute(String path, boolean readsAccount, String preAuthorize) {
            this.path = path;
            this.readsAccount = readsAccount;
            this.preAuthorize = preAuthorize;
        }
    }
}
