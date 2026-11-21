FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace

RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 sh mvnw -B -DskipTests package

# The game uses L32X64MixRandom from the jdk.random module.
FROM eclipse-temurin:21-jdk-noble
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && install -d -o 10001 -g 10001 /data

COPY --from=build /workspace/target/*.jar /app/sllapp.jar

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/sllapp.jar"]
