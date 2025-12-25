FROM eclipse-temurin:17-jre

WORKDIR /app

# Install curl for health checks
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# Copy the built jar
COPY target/DynaMQ-1.0-SNAPSHOT.jar app.jar
COPY src/main/resources/application.yml application.yml
COPY src/main/resources/cluster.xml cluster.xml

# JVM optimization for high-concurrency MQTT (5GB per node)
# ZGC: Ultra-low latency GC, ideal for IoT with millions of connections
# Direct memory for Netty I/O buffers
ENV JAVA_OPTS="-Xms4g -Xmx5g \
    -XX:+UseZGC \
    -XX:MaxDirectMemorySize=1g \
    -XX:+AlwaysPreTouch \
    -XX:MaxGCPauseMillis=10 \
    -Dio.netty.recycler.maxCapacityPerThread=0 \
    -Dio.netty.leakDetection.level=disabled"

EXPOSE 1883 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
