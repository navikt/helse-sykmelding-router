FROM navikt/java:11

COPY config.json .
COPY build/install/helse-sykmelding-router/bin/helse-sykmelding-router bin/app
COPY build/install/helse-sykmelding-router/lib lib/
ENV JAVA_OPTS="-XshowSettings:vm -Dlogback.configurationFile=logback-remote.xml -XX:MaxRAMPercentage=75"
