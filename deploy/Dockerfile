FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy the built jar
COPY target/DynaMQ-1.0-SNAPSHOT.jar app.jar
COPY src/main/resources/application.yml application.yml
COPY src/main/resources/cluster.xml cluster.xml

# Set environment variables
ENV JAVA_OPTS="-Xms256m -Xmx512m"

EXPOSE 1883 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
