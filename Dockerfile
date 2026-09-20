# syntax=docker/dockerfile:1
FROM maven:3.8.8-eclipse-temurin-8 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -Dmaven.test.skip=true -Dmaven.wagon.http.retryHandler.count=5 package

FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=build /workspace/target/hm-dianping-0.0.1-SNAPSHOT.jar app.jar
RUN mkdir -p /app/uploads
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
