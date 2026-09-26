# ghcr.io/panomc/pano:<version> — Pano with its release bundled, for self-hosters' Docker installs.
#
# The pano-runtime image (launcher, tini, Bun, non-root uid 10000) plus the release jar in
# /opt/pano/release. pano-seed copies it into the /data volume on first start and whenever the image
# carries a different release than the one it seeded last; an in-panel self-update in between stays in
# place (it moves /data/.pano-jar itself). Everything Pano writes lives in /data.
#
# CI copies build/libs/Pano-<version>.jar (+ .sha256) into docker/pano/release/ first:
#   docker build --build-arg RUNTIME=ghcr.io/panomc/pano-runtime:jre11 -t ghcr.io/panomc/pano:dev docker/pano
#   docker run -p 8088:8088 -v pano-data:/data -e PANO_DB_HOST=... ghcr.io/panomc/pano:dev
ARG RUNTIME=ghcr.io/panomc/pano-runtime:jre11
FROM ${RUNTIME}

LABEL org.opencontainers.image.title="pano"
LABEL org.opencontainers.image.description="Pano, the web platform for Minecraft servers, with its release bundled."
LABEL org.opencontainers.image.source="https://github.com/PanoMC/Pano"
LABEL org.opencontainers.image.licenses="MIT"

COPY pano-seed.sh /usr/local/bin/pano-seed
COPY release/ /opt/pano/release/

USER 0:0
RUN set -eux; \
    chmod 0755 /usr/local/bin/pano-seed; \
    chmod -R a+rX,go-w /opt/pano/release; \
    count=$(ls /opt/pano/release/Pano-*.jar | wc -l); \
    [ "$count" = 1 ] || { echo "docker/pano/release must hold exactly one Pano-*.jar, found $count" >&2; exit 1; }; \
    if ls /opt/pano/release/*.sha256 >/dev/null 2>&1; then cd /opt/pano/release && sha256sum -c ./*.sha256; fi
USER 10000:10000

VOLUME /data
ENTRYPOINT ["tini", "-s", "--", "/usr/local/bin/pano-seed"]
