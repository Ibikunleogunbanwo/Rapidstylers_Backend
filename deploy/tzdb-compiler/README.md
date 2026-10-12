# Time zone rules for the production image

The `Dockerfile` replaces the JRE's `lib/tzdb.dat` with rules compiled from the
IANA release in `deploy/tzdata/<version>/`, so the API does not depend on how
recent the `eclipse-temurin:17-jre` image on the server happens to be.

* `deploy/tzdata/2026e/` — IANA tz source files from tag `2026e` of
  https://github.com/eggert/tz (public domain, see `LICENSE` there), plus `gmt`
  and `jdk11_backward` from OpenJDK, which the JDK always compiles in.
* `build/tools/tzdb/` — OpenJDK's own tzdb compiler, unmodified, from
  `openjdk/jdk17u` commit `3b0480102f8595deb241a666498f77644d5ec376`
  (`make/jdk/src/classes/build/tools/tzdb/` and, with the package line changed
  exactly as the JDK build does, `src/java.base/share/classes/java/time/zone/`
  `ZoneRules`, `ZoneOffsetTransition`, `ZoneOffsetTransitionRule`, `Ser`).
  Licence: GPL v2 with the Classpath exception (headers kept). It runs only in a
  build stage; neither the tool nor its classes are in the shipped image.
* `TzdbCheck.java` and `RuntimeTzCheck.java` are ours: the image build fails
  unless the compiled file, and then the runtime JVM, report the pinned version
  and the expected Canadian offsets.

Compiling the JDK's own 2026b sources with this tool reproduces Temurin
17.0.20.1's `tzdb.dat` byte for byte.

## Moving to a newer IANA release

1. Download the nine source files (`africa antarctica asia australasia europe
   northamerica southamerica backward etcetera`) and `LICENSE` for the new tag
   from `https://raw.githubusercontent.com/eggert/tz/<tag>/<file>` into
   `deploy/tzdata/<tag>/`; copy `gmt` and `jdk11_backward` across and write
   `tzdata<tag>` into `VERSION`.
2. Set `ARG TZDATA_VERSION=<tag>` in the `Dockerfile` and update the offsets in
   the two check classes if the new release changes them.
3. `mvn test -Dtest=TimeZoneDataBuildTest`, then rebuild the image. The startup
   log line `Time zone data: tzdb <version>` confirms what a server is running.
