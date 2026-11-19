FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

ENV MAVEN_OPTS="-Xms64m -Xmx256m -XX:+UseSerialGC"

# Copy pom and run dependency:go-offline first to cache dependencies
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw && ./mvnw dependency:go-offline

# Copy source and build
COPY src ./src
RUN ./mvnw -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-Xms32m", "-Xmx160m", "-XX:+UseSerialGC", "-XX:MaxMetaspaceSize=160m", "-XX:ReservedCodeCacheSize=16m", "-XX:MaxDirectMemorySize=32m", "-jar", "app.jar"]
