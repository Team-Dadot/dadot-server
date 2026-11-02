FROM eclipse-temurin:21-jre-noble

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 dadot \
    && useradd --uid 10001 --gid 10001 --no-create-home \
       --home-dir /app --shell /usr/sbin/nologin dadot \
    && mkdir -p /app/data/originals /run/certs \
    && chown -R 10001:10001 /app

WORKDIR /app

COPY --chown=10001:10001 build/libs/dadot-server-0.0.1-SNAPSHOT.jar /app/app.jar

USER 10001:10001

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]