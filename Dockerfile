FROM navikt/java:11

COPY config-preprod.json .
COPY config-prod.json .
COPY build/libs/helse-sykmelding-router-*-all.jar app.jar
ENV JAVA_OPTS="-XshowSettings:vm -Dlogback.configurationFile=logback-remote.xml -XX:MaxRAMPercentage=75"
