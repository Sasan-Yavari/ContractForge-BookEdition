FROM eclipse-temurin:26-jdk AS build
WORKDIR /workspace
COPY backend/mvnw backend/pom.xml ./backend/
COPY backend/.mvn/ ./backend/.mvn/
RUN chmod +x backend/mvnw && cd backend && ./mvnw -q -DskipTests dependency:go-offline
COPY backend/src ./backend/src
RUN cd backend && ./mvnw -q -DskipTests package

FROM eclipse-temurin:26-jre
RUN apt-get update && apt-get install -y --no-install-recommends wget \
    && groupadd --system --gid 10001 contractforge \
    && useradd --system --uid 10001 --gid contractforge --no-create-home \
      --home-dir /nonexistent --shell /usr/sbin/nologin contractforge \
    && chmod 1777 /tmp \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/backend/target/contractforge-backend-0.0.1-SNAPSHOT.jar app.jar
VOLUME ["/tmp"]
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
