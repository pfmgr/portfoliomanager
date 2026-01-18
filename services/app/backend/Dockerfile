FROM gradle:9.2-jdk21 AS gradle

FROM postgres:16 AS build
USER root
WORKDIR /app
COPY --from=gradle /opt/java/openjdk /opt/java/openjdk
COPY --from=gradle /opt/gradle /opt/gradle
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH=/opt/gradle/bin:/opt/java/openjdk/bin:$PATH
ENV DB_PORT=5439
ENV DB_NAME=mydatabase
ENV DB_URL=jdbc:postgresql://localhost:$DB_PORT/$DB_NAME
ENV DB_USER=myuser
ENV DB_PASS=secret
COPY build.gradle settings.gradle gradlew gradlew.bat ./
COPY gradle ./gradle
COPY src ./src
RUN set -eux; \
    mkdir -p /tmp/pgdata; \
    chown -R postgres:postgres /tmp/pgdata; \
    gosu postgres initdb -D /tmp/pgdata --no-locale --encoding=UTF8; \
    echo "max_connections = 200" >> /tmp/pgdata/postgresql.conf; \
    gosu postgres pg_ctl -D /tmp/pgdata -o "-c listen_addresses=127.0.0.1 -p ${DB_PORT}" -w start; \
    gosu postgres psql -p $DB_PORT -c "CREATE USER ${DB_USER} WITH PASSWORD '${DB_PASS}';"; \
    gosu postgres createdb -p $DB_PORT -O $DB_USER $DB_NAME; \
    /opt/gradle/bin/gradle clean check bootJar --no-daemon; \
    gosu postgres pg_ctl -D /tmp/pgdata -m fast stop

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
