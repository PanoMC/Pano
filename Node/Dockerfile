# The pano-node daemon as a container, for hosts that are managed rather than logged into.
#
# The jar is copied in rather than built here on purpose: the release workflow has already built
# and checksummed exactly the jar that is published as a release asset, and building it a second
# time in a different environment would mean shipping an image whose contents nobody verified
# against that checksum.
#
# Build from the repository root, after ./gradlew :Node:shadowJar:
#   docker build -f Node/Dockerfile -t ghcr.io/panomc/pano-node:dev Node
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="pano-node"
LABEL org.opencontainers.image.description="Installs and supervises Minecraft servers for a Pano platform."
LABEL org.opencontainers.image.source="https://github.com/PanoMC/pano"
LABEL org.opencontainers.image.licenses="MIT"

# Everything the daemon remembers — its pairing, its servers and their worlds — lives here, so
# this is the one path that has to survive a redeploy.
ENV PANO_NODE_DATA=/data

# The ports managed servers bind by default. Published rather than assumed: a node whose servers
# cannot be reached from outside the container is a node that installs games nobody can join.
# Publishing other ports? Set PANO_NODE_PORT_RANGE (e.g. 25660-25669) to the same range, or the
# node keeps handing out ports from 25565-25600 that the container does not publish.
EXPOSE 25565-25600

RUN mkdir -p /data

VOLUME /data

WORKDIR /data

COPY build/libs/pano-node.jar /app/pano-node.jar

# No pairing arguments: a container is configured through its environment (PANO_URL,
# PANO_BOOTSTRAP_TOKEN, PANO_NODE_NAME, PANO_NODE_PORT_RANGE), which is the only way a deployment
# platform can configure it at all.
ENTRYPOINT ["java", "-jar", "/app/pano-node.jar"]
