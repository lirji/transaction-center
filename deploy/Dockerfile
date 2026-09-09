ARG TRADE_JAVA_IMAGE=eclipse-temurin:21-jre
FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY transaction-client-sdk/ transaction-client-sdk/
COPY transaction-core/ transaction-core/
COPY transaction-member/ transaction-member/
COPY transaction-catalog/ transaction-catalog/
COPY transaction-inventory/ transaction-inventory/
COPY transaction-order/ transaction-order/
COPY transaction-payment/ transaction-payment/
COPY transaction-refund/ transaction-refund/
COPY transaction-jobs/ transaction-jobs/
COPY transaction-server/ transaction-server/
# 数据库验收由verify.sh单独执行，镜像构建不启动测试数据库。
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode -DskipTests package

FROM ${TRADE_JAVA_IMAGE} AS runtime-base
WORKDIR /app
USER 10001:10001
EXPOSE 8400
# 使用镜像自带bash读取完整健康状态，数据库不可用时不能报告健康。
HEALTHCHECK --interval=15s --timeout=5s --start-period=300s --retries=4 \
  CMD bash -ec 'exec 3<>/dev/tcp/127.0.0.1/8400; printf "GET /actuator/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n" >&3; grep -q '\''"status":"UP"'\'' <&3'
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]

# 本机有Java/Maven时可复用本机依赖缓存，避免首次构建镜像下载阻塞。
FROM runtime-base AS prebuilt
COPY transaction-server/target/transaction-server-0.1.0-SNAPSHOT.jar app.jar

FROM runtime-base AS runtime
COPY --from=build /src/transaction-server/target/transaction-server-0.1.0-SNAPSHOT.jar app.jar
