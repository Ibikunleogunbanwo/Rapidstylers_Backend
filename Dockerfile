# Time zone rules: the JRE's own lib/tzdb.dat is only as new as the JRE build,
# and Canada changed its rules in 2026 (BC, Alberta, NWT and Manitoba stay on
# permanent daylight time from 2026-11-01). The "tzdb" stage compiles the IANA
# source vendored in deploy/tzdata/<version>/ with OpenJDK's own compiler
# (deploy/tzdb-compiler/) and the runtime stage swaps it in, then refuses to
# finish unless the JVM reports that version and the expected offsets.
# To move to a newer IANA release: add deploy/tzdata/<new>/ and bump TZDATA_VERSION.
ARG TZDATA_VERSION=2026e

# ---- Time zone data stage ----
FROM eclipse-temurin:17-jdk AS tzdb
ARG TZDATA_VERSION
WORKDIR /tzdb
COPY deploy/tzdb-compiler/ tool/
COPY deploy/tzdata/${TZDATA_VERSION}/ data/
RUN javac --release 17 -d classes tool/build/tools/tzdb/*.java \
 && javac --release 17 -d check tool/RuntimeTzCheck.java \
 && java -cp classes build.tools.tzdb.TzdbZoneRulesCompiler -srcdir data -dstfile tzdb.dat \
      africa antarctica asia australasia europe northamerica southamerica backward etcetera gmt jdk11_backward \
 && java -cp classes build.tools.tzdb.TzdbCheck tzdb.dat ${TZDATA_VERSION}

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# Cache dependencies first: copy only the pom, download, then copy sources.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre
ARG TZDATA_VERSION
COPY --from=tzdb /tzdb/tzdb.dat ${JAVA_HOME}/lib/tzdb.dat
COPY --from=tzdb /tzdb/check /tmp/tzcheck
RUN java -cp /tmp/tzcheck RuntimeTzCheck ${TZDATA_VERSION} && rm -rf /tmp/tzcheck
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system spring && useradd --system --gid spring spring
COPY --from=build /build/target/*.jar app.jar
USER spring
EXPOSE 9095
# Container memory limits are honored by the JVM via MaxRAMPercentage.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60.0", "-jar", "app.jar"]
