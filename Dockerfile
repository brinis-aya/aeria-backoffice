FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S aeria && adduser -S aeria -G aeria
WORKDIR /app
COPY --from=build /src/target/aeria-backoffice.jar app.jar
USER aeria
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8084
HEALTHCHECK --interval=30s --timeout=5s CMD wget -qO- http://127.0.0.1:8084/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
