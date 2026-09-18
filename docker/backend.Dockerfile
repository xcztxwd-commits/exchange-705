FROM dockerproxy.net/library/tomcat:9.0-jdk8-temurin AS build
RUN curl -fLsS --retry 3 https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.tar.gz | tar -xz -C /opt
WORKDIR /build
COPY exchange-backend/pom.xml ./
COPY exchange-backend/src ./src
RUN --mount=type=cache,target=/root/.m2 /opt/apache-maven-3.9.9/bin/mvn -B package

FROM build AS test
RUN --mount=type=cache,target=/root/.m2 cp -a /root/.m2 /opt/maven-cache

FROM dockerproxy.net/library/tomcat:9.0-jdk8-temurin
WORKDIR /app
COPY --from=build /build/target/exchange-backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx512m", "-jar", "app.jar"]

