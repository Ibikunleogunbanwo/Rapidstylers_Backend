package com.macrotel.rapidstylers.common.repo;

import com.macrotel.rapidstylers.catalog.entity.BlogPostEntity;
import com.macrotel.rapidstylers.catalog.entity.ServiceEntity;
import com.macrotel.rapidstylers.engagement.entity.NotificationEntity;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.util.ClassUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks every derived query method name against the fields of the entity it runs against.
 *
 * <p>Spring Data resolves a derived query from the method name while the repository
 * interface is being built, so a name that mentions a property the entity does not have
 * does not quietly return nothing: the repository bean fails and the application does not
 * start. That is how {@code ServiceRepo.countByImageUrl} reached CI. A service stores its
 * picture in {@code serviceImageUrl}, so the count had no property to bind to and would
 * have crash-looped production the way the September encryption incident did.
 *
 * <p>Nothing caught it locally, and not for a shallow reason: repository interfaces are
 * only built when a Spring context starts, so the twelve {@code @SpringBootTest} classes
 * are the only local coverage for this, and they cannot finish on a developer machine,
 * where each one boots a context and retries Kafka for minutes. This test needs no context
 * at all. It reads the repository interfaces and their entities with reflection, parses
 * the names the way Spring Data does, and answers in milliseconds.
 *
 * <p>Scope, honestly: it checks that every property a derived name mentions exists on the
 * entity, and that the criteria a name describes match the method's parameters. It does
 * not check property types, keyword-type compatibility, or anything inside a {@code @Query}.
 */
class DerivedQueryNamesTest {

    /** The root package, so a repository added in any subpackage is covered by the scan. */
    private static final String BASE_PACKAGE = "com.macrotel.rapidstylers";

    /**
     * Floors that keep this test from passing vacuously: a validator that inspects nothing
     * always passes, so if discovery or parsing ever stops working these fail first and say
     * so. Today the scan finds 28 repository interfaces holding 75 derived queries across
     * two packages, so the values sit comfortably below that.
     */
    private static final int MIN_REPOSITORIES = 25;
    private static final int MIN_DERIVED_METHODS = 60;
    private static final int MIN_PACKAGES = 2;

    /** The query subjects Spring Data recognises, longest first so `exists` wins over `e`. */
    private static final List<String> SUBJECTS = List.of(
            "find", "read", "get", "query", "search", "stream", "count", "exists", "delete", "remove");

    private static final String ORDER_BY = "OrderBy";

    /** `Distinct`, then `First`/`Top` with an optional row limit, all before the `By`. */
    private static final Pattern MODIFIERS = Pattern.compile("^(Distinct)?(First|Top)?(\\d+)?");

    /** The `And`/`Or` that separate criteria, matching only where a property name follows. */
    private static final Pattern CONJUNCTION = Pattern.compile("(And|Or)(?=[A-Z])");

    /**
     * The predicates Spring Data accepts at the tail of a criterion, and how many of the
     * method's parameters each one binds. A criterion with no keyword is a plain equality
     * and binds one parameter. A keyword absent from this map means the parameter count is
     * unknown, and the name is then checked for properties only rather than guessed at.
     */
    private static final Map<String, Integer> KEYWORDS = Map.ofEntries(
            // No parameter of their own.
            Map.entry("True", 0), Map.entry("IsTrue", 0),
            Map.entry("False", 0), Map.entry("IsFalse", 0),
            Map.entry("Null", 0), Map.entry("IsNull", 0),
            Map.entry("NotNull", 0), Map.entry("IsNotNull", 0),
            Map.entry("Empty", 0), Map.entry("IsEmpty", 0),
            Map.entry("NotEmpty", 0), Map.entry("IsNotEmpty", 0),
            Map.entry("Exists", 0), Map.entry("NotExists", 0),
            // One parameter.
            // `IgnoreCase`/`AllIgnoreCase` modify the equality rather than replacing it, so
            // `findByEmailIgnoreCase` still binds the address it compares against.
            Map.entry("IgnoreCase", 1), Map.entry("AllIgnoreCase", 1),
            Map.entry("Is", 1), Map.entry("Not", 1),
            Map.entry("In", 1), Map.entry("NotIn", 1),
            Map.entry("LessThan", 1), Map.entry("LessThanEqual", 1),
            Map.entry("GreaterThan", 1), Map.entry("GreaterThanEqual", 1),
            Map.entry("Before", 1), Map.entry("After", 1),
            Map.entry("Like", 1), Map.entry("NotLike", 1),
            Map.entry("Containing", 1), Map.entry("NotContaining", 1),
            Map.entry("StartingWith", 1), Map.entry("EndingWith", 1),
            Map.entry("Regex", 1),
            // Two: the bounds of a range.
            Map.entry("Between", 2));

    /** Parameter types Spring Data binds itself, rather than from the criteria. */
    private static final Set<String> UNBOUND_PARAMETER_TYPES = Set.of(
            "org.springframework.data.domain.Sort",
            "org.springframework.data.domain.Pageable",
            "org.springframework.data.domain.PageRequest",
            "org.springframework.data.domain.Limit");

    @Test
    void everyDerivedQueryNamesPropertiesItsEntityHas() {
        List<String> problems = new ArrayList<>();
        Set<String> packages = new TreeSet<>();
        int repositories = 0;
        int derivedMethods = 0;

        for (Class<?> repository : repositories()) {
            repositories++;
            packages.add(repository.getPackageName());

            Class<?> entity = entityOf(repository);
            for (Method method : repository.getDeclaredMethods()) {
                if (isNotADerivedQuery(repository, method)) {
                    continue;
                }
                derivedMethods++;
                problems.addAll(nameProblems(
                        repository.getSimpleName() + "." + method.getName(),
                        method.getName(),
                        entity,
                        boundParameters(method)));
            }
        }

        // Copied into finals so the failure messages can read them.
        int foundRepositories = repositories;
        int foundMethods = derivedMethods;
        Set<String> foundPackages = Set.copyOf(packages);

        assertTrue(foundRepositories >= MIN_REPOSITORIES, () -> "Only " + foundRepositories
                + " repository interfaces were found, so discovery is broken and nothing was checked.");
        assertTrue(foundPackages.size() >= MIN_PACKAGES, () -> "Repositories were found in only " + foundPackages
                + ", so the scan is not looking inside subpackages.");
        assertTrue(foundMethods >= MIN_DERIVED_METHODS, () -> "Only " + foundMethods
                + " derived query methods were found to check, which is far fewer than this"
                + " repository layer declares, so the scan is skipping them.");

        assertTrue(problems.isEmpty(), () -> "These derived query names cannot be resolved, and Spring Data"
                + " resolves them while the repository is built, so each one stops the application from"
                + " starting:\n  " + String.join("\n  ", problems));
    }

    /**
     * The incident, as an assertion, so the validator cannot be quietly neutered: this is
     * the name that reached CI, on the entity that does not have the field it names.
     */
    @Test
    void itCatchesTheFieldMismatchThatReachedCi() {
        List<String> problems = nameProblems(
                "ServiceRepo.countByImageUrl", "countByImageUrl", ServiceEntity.class, 1);

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("ServiceEntity"), problems::toString);
        assertTrue(problems.get(0).contains("imageUrl"), problems::toString);
        assertTrue(problems.get(0).contains("serviceImageUrl"),
                () -> "the message should point at the field that does exist: " + problems);
    }

    /** The other half of that incident: the same name is right on the entity that does store it. */
    @Test
    void theSameNameIsCorrectOnTheEntityThatDoesStoreIt() {
        assertEquals(List.<String>of(), nameProblems(
                "BlogPostRepo.countByImageUrl", "countByImageUrl", BlogPostEntity.class, 1));
    }

    /** Keywords with a parameter count of their own, so `ReadFalse` binds nothing extra. */
    @Test
    void itReadsAKeywordThatBindsNoParameterOfItsOwn() {
        assertEquals(List.<String>of(), nameProblems(
                "NotificationRepo.countByUserIdAndReadFalse",
                "countByUserIdAndReadFalse", NotificationEntity.class, 1));
    }

    @Test
    void itCatchesAParameterThatDoesNotMatchTheCriteria() {
        List<String> problems = nameProblems(
                "NotificationRepo.countByUserIdAndReadFalse",
                "countByUserIdAndReadFalse", NotificationEntity.class, 2);

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("1 parameter"), problems::toString);
    }

    // ---------------------------------------------------------------- the name parser

    /** A criterion read from a name: the property it names and the keyword that follows it. */
    private record Criterion(String property, String keyword) {}

    /**
     * Everything about one derived query name that Spring Data would refuse to build, in a
     * form the caller can assert on. {@code declaredParameters} is null when the caller has
     * no method to take a parameter count from.
     */
    static List<String> nameProblems(String where, String methodName, Class<?> entity, Integer declaredParameters) {
        List<String> problems = new ArrayList<>();

        String rest = afterSubject(methodName);
        if (rest == null) {
            problems.add(where + ": the name has no query subject (find, read, get, query, search, stream,"
                    + " count, exists, delete, remove), so there is nothing for Spring Data to derive.");
            return problems;
        }

        rest = MODIFIERS.matcher(rest).replaceFirst("");
        if (rest.isEmpty() || rest.equals("All")) {
            return problems;    // subject only, as in `findAll`: no criteria, nothing to check
        }

        String criteria;
        String order;
        if (rest.startsWith("By")) {
            int orderAt = rest.indexOf(ORDER_BY);
            criteria = orderAt < 0 ? rest.substring(2) : rest.substring(2, orderAt);
            order = orderAt < 0 ? "" : rest.substring(orderAt + ORDER_BY.length());
        } else if (rest.startsWith(ORDER_BY)) {
            criteria = "";
            order = rest.substring(ORDER_BY.length());
        } else {
            problems.add(where + ": expected a `By` clause after the subject, found `" + rest
                    + "`, which Spring Data would read as a property of " + entity.getSimpleName() + ".");
            return problems;
        }

        boolean parametersKnown = true;
        int expectedParameters = 0;

        for (String part : splitCriteria(criteria)) {
            Criterion criterion = parsePart(entity, part);
            if (criterion == null) {
                problems.add(where + ": " + entity.getSimpleName() + " has no property matching `"
                        + uncapitalize(part) + "`" + closestField(entity, part) + ".");
                parametersKnown = false;
                continue;
            }
            Integer arity = criterion.keyword().isEmpty() ? 1 : KEYWORDS.get(criterion.keyword());
            if (arity == null) {
                parametersKnown = false;
            } else {
                expectedParameters += arity;
            }
        }

        for (String property : splitOrder(order)) {
            if (!resolvesOn(entity, property)) {
                problems.add(where + ": " + entity.getSimpleName() + " has no property matching `"
                        + uncapitalize(property) + "` to sort by" + closestField(entity, property) + ".");
            }
        }

        if (parametersKnown && declaredParameters != null && declaredParameters != expectedParameters) {
            problems.add(where + ": the criteria read from the name bind " + expectedParameters
                    + " parameter(s), but the method declares " + declaredParameters + ".");
        }

        return problems;
    }

    /** The name after its query subject, or null when it has none. */
    private static String afterSubject(String methodName) {
        for (String subject : SUBJECTS) {
            if (methodName.startsWith(subject) && methodName.length() > subject.length()) {
                return methodName.substring(subject.length());
            }
        }
        return null;
    }

    /**
     * The longest property that resolves, with a recognised keyword after it. Spring Data
     * decides the same way, which is what lets `StatusIn` be the property `status` with the
     * `In` predicate rather than a property called `statusIn`.
     */
    private static Criterion parsePart(Class<?> entity, String part) {
        for (int split = part.length(); split >= 1; split--) {
            String keyword = part.substring(split);
            if (!keyword.isEmpty() && !KEYWORDS.containsKey(keyword)) {
                continue;
            }
            String property = part.substring(0, split);
            if (resolvesOn(entity, property)) {
                return new Criterion(property, keyword);
            }
        }
        return null;
    }

    /** The criteria in a `By` clause, split on the `And`/`Or` between them. */
    private static List<String> splitCriteria(String criteria) {
        if (criteria.isEmpty()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        var matcher = CONJUNCTION.matcher(criteria);
        while (matcher.find()) {
            parts.add(criteria.substring(start, matcher.start()));
            start = matcher.end();
        }
        parts.add(criteria.substring(start));
        return parts;
    }

    /** The ordered properties in an `OrderBy` clause, each with its direction stripped. */
    private static List<String> splitOrder(String order) {
        List<String> properties = new ArrayList<>();
        for (String block : order.split("(?<=Asc)|(?<=Desc)")) {
            if (block.isEmpty()) {
                continue;
            }
            if (block.endsWith("Desc")) {
                properties.add(block.substring(0, block.length() - 4));
            } else if (block.endsWith("Asc")) {
                properties.add(block.substring(0, block.length() - 3));
            } else {
                properties.add(block);    // an absent direction means ascending
            }
        }
        return properties;
    }

    // ------------------------------------------------------------- entity properties

    /** Whether the entity, or whatever it extends, has this property path. */
    private static boolean resolvesOn(Class<?> type, String path) {
        if (path.isEmpty()) {
            return false;
        }
        if (path.contains("_")) {
            Class<?> current = type;
            for (String segment : path.split("_")) {
                current = propertyType(current, segment);
                if (current == null) {
                    return false;
                }
            }
            return true;
        }
        if (propertyType(type, path) != null) {
            return true;
        }
        // An association written in camel case, as in `ByStylerName` for `styler.name`.
        for (int split = 1; split < path.length(); split++) {
            if (!Character.isUpperCase(path.charAt(split))) {
                continue;
            }
            Class<?> nested = propertyType(type, path.substring(0, split));
            if (nested != null && resolvesOn(nested, path.substring(split))) {
                return true;
            }
        }
        return false;
    }

    /** The type of a persistent property, or null when the entity does not have one. */
    private static Class<?> propertyType(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (hasAnnotationNamed(field, "Transient")) {
                    continue;    // not part of the JPA metamodel, so not a usable property
                }
                if (matches(field.getName(), name)) {
                    return field.getType();
                }
            }
            for (Method getter : current.getDeclaredMethods()) {
                if (Modifier.isStatic(getter.getModifiers()) || getter.getParameterCount() != 0) {
                    continue;
                }
                String property = propertyOf(getter.getName());
                if (property != null && matches(property, name)) {
                    return getter.getReturnType();
                }
            }
        }
        return null;
    }

    /** `getX`/`isX` read as the property `x`, or null when the method is not a property getter. */
    private static String propertyOf(String getterName) {
        if (getterName.startsWith("get") && getterName.length() > 3) {
            return uncapitalize(getterName.substring(3));
        }
        if (getterName.startsWith("is") && getterName.length() > 2) {
            return uncapitalize(getterName.substring(2));
        }
        return null;
    }

    /** Spring Data resolves a name segment against the uncapitalised field name. */
    private static boolean matches(String candidate, String name) {
        return candidate.equals(name) || candidate.equals(uncapitalize(name));
    }

    private static String uncapitalize(String name) {
        if (name.isEmpty() || Character.isLowerCase(name.charAt(0))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /** A nudge towards the field the author probably meant, since that is the whole fix. */
    private static String closestField(Class<?> entity, String property) {
        String wanted = property.toLowerCase(Locale.ROOT);
        for (Class<?> current = entity; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                String candidate = field.getName().toLowerCase(Locale.ROOT);
                if (candidate.contains(wanted) || wanted.contains(candidate)) {
                    return ". Closest field on it is `" + field.getName() + "`";
                }
            }
        }
        return "";
    }

    // ------------------------------------------------------------- repository scanning

    /**
     * Every repository interface under the root package. The scan reads bytecode, so it
     * needs no Spring context and never instantiates a repository.
     */
    private static List<Class<?>> repositories() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                        // Repository types are interfaces, which the default rule rejects.
                        return definition.getMetadata().isInterface() && definition.getMetadata().isIndependent();
                    }
                };
        scanner.addIncludeFilter(new AssignableTypeFilter(JpaRepository.class));

        Set<Class<?>> found = new TreeSet<>(Comparator.comparing(Class::getName));
        for (BeanDefinition candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            found.add(ClassUtils.resolveClassName(candidate.getBeanClassName(), ClassUtils.getDefaultClassLoader()));
        }
        return new ArrayList<>(found);
    }

    /** A method is not a derived query when it is hand-written SQL, a default method, or a framework method. */
    private static boolean isNotADerivedQuery(Class<?> repository, Method method) {
        if (method.isDefault() || method.isSynthetic() || method.isBridge()
                || Modifier.isStatic(method.getModifiers())) {
            return true;
        }
        if (hasAnnotationNamed(method, "Query") || hasAnnotationNamed(method, "Procedure")
                || hasAnnotationNamed(method, "NativeQuery")) {
            return true;    // its name describes SQL columns, not entity properties
        }
        // A redeclaration of a framework method (`findAll`) is served by the base
        // repository, so Spring never derives a query from its name either.
        for (Class<?> parent : superInterfaces(repository)) {
            try {
                parent.getMethod(method.getName(), method.getParameterTypes());
                return true;
            } catch (NoSuchMethodException notDeclaredThere) {
                // keep looking up the hierarchy
            }
        }
        return false;
    }

    private static Set<Class<?>> superInterfaces(Class<?> type) {
        Set<Class<?>> found = new LinkedHashSet<>();
        collectSuperInterfaces(type, found);
        return found;
    }

    private static void collectSuperInterfaces(Class<?> type, Set<Class<?>> into) {
        for (Class<?> parent : type.getInterfaces()) {
            if (into.add(parent)) {
                collectSuperInterfaces(parent, into);
            }
        }
    }

    /** The entity a repository manages, from its `JpaRepository<Entity, Id>` declaration. */
    private static Class<?> entityOf(Class<?> repository) {
        for (Type type : repository.getGenericInterfaces()) {
            Class<?> entity = domainType(type);
            if (entity != null) {
                return entity;
            }
        }
        return fail("Could not work out the entity that " + repository.getSimpleName()
                + " manages, so its derived query names cannot be checked. Expecting"
                + " `extends JpaRepository<Entity, Id>`.");
    }

    private static Class<?> domainType(Type type) {
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            if (JpaRepository.class.equals(raw)) {
                Type entity = parameterized.getActualTypeArguments()[0];
                return entity instanceof Class<?> concrete ? concrete : null;
            }
            for (Type parent : raw.getGenericInterfaces()) {
                Class<?> found = domainType(parent);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (type instanceof Class<?> raw) {
            for (Type parent : raw.getGenericInterfaces()) {
                Class<?> found = domainType(parent);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The parameters the criteria bind, ignoring the `Sort`/`Pageable` Spring Data supplies. */
    private static int boundParameters(Method method) {
        Class<?>[] types = method.getParameterTypes();
        int bound = types.length;
        while (bound > 0 && UNBOUND_PARAMETER_TYPES.contains(types[bound - 1].getName())) {
            bound--;
        }
        return bound;
    }

    /** Matches by simple name, so this works whichever of the JPA/Spring Data packages is in use. */
    private static boolean hasAnnotationNamed(AnnotatedElement element, String simpleName) {
        for (Annotation annotation : element.getAnnotations()) {
            if (annotation.annotationType().getSimpleName().equals(simpleName)) {
                return true;
            }
        }
        return false;
    }
}
