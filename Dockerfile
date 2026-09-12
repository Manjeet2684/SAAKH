FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# only-script Maven wrapper needs curl and unzip; this is not a second Maven install.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src src
RUN chmod +x mvnw && ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --no-create-home --uid 1001 saakh
COPY --from=build /src/target/apexledger-0.1.0-SNAPSHOT.jar /app/app.jar
RUN chown saakh:saakh /app/app.jar
USER saakh
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
