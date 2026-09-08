FROM eclipse-temurin:21-jre
WORKDIR /app
COPY transaction-server/target/transaction-server-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]

