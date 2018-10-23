FROM navikt/java:11

COPY altinnkanal/build/install/helse-sykmelding-router/bin/altinnkanal bin/app
COPY altinnkanal/build/install/helse-sykmelding-router/lib lib/
ENV JAVA_OPTS="-XshowSettings:vm -Dlogback.configurationFile=logback-remote.xml -XX:MaxRAMPercentage=75"
