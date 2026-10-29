FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
COPY --from=build /src/target/gps-backfill.jar /app.jar
EXPOSE 8080 8081
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app.jar"]
