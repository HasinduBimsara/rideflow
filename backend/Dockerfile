FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/

# Handle Windows line endings in the Maven wrapper.
RUN sed -i 's/\r$//' mvnw

# Tests run separately in GitHub Actions with infrastructure.
RUN bash ./mvnw --batch-mode --no-transfer-progress \
    -DskipTests clean package

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY --from=build \
    /workspace/target/backend-0.0.1-SNAPSHOT.jar \
    /app/app.jar

USER 10001:10001

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]