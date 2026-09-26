# ghcr.io/panomc/pano-runtime:jre<N> — the Pano Host runtime for a Pano Instance.
#
# The Portal agent runs it with runtimeLauncher=image: no command, no entrypoint override, the app dir
# bind-mounted at /data (workdir, HOME), an arbitrary --user uid:uid, --read-only, --cap-drop ALL,
# no-new-privileges, tmpfs /tmp and PANO_JVM_ARGS (-Xmx + customer JVM args). The launcher runs the jar
# named in /data/.pano-jar and relaunches it in place when Pano exits with 75 (planned restart/update).
#
#   docker build --build-arg JRE=21 -t ghcr.io/panomc/pano-runtime:jre21 docker/runtime
ARG JRE=11
FROM eclipse-temurin:${JRE}-jre-noble

ARG BUN_VERSION=bun-v1.4.2
ARG TARGETARCH

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends tini unzip; \
    arch="${TARGETARCH:-$(dpkg --print-architecture)}"; \
    case "$arch" in \
      amd64) bun=bun-linux-x64-baseline ;; \
      arm64) bun=bun-linux-aarch64 ;; \
      *) echo "unsupported architecture: $arch" >&2; exit 1 ;; \
    esac; \
    cd /tmp; \
    base="https://github.com/oven-sh/bun/releases/download/${BUN_VERSION}"; \
    curl -fsSLO "$base/$bun.zip"; \
    curl -fsSL "$base/SHASUMS256.txt" | grep " $bun.zip\$" | sha256sum -c -; \
    unzip -q "$bun.zip"; \
    install -D -m 0755 "$bun/bun" /opt/pano/bun; \
    rm -rf "/tmp/$bun" "/tmp/$bun.zip"; \
    /opt/pano/bun --version; \
    apt-get purge -y --auto-remove unzip; \
    rm -rf /var/lib/apt/lists/*; \
    groupadd --system --gid 10000 pano; \
    useradd --system --uid 10000 --gid 10000 --home-dir /data --no-create-home --shell /usr/sbin/nologin pano; \
    install -d -m 0775 -o 10000 -g 10000 /data

COPY pano-launcher.sh /usr/local/bin/pano-launcher
RUN chmod 0755 /usr/local/bin/pano-launcher

ENV PANO_CONTAINER=1 \
    PANO_BUN_VERSION=${BUN_VERSION} \
    HOME=/data

USER 10000:10000
WORKDIR /data
EXPOSE 8088

ENTRYPOINT ["tini", "-s", "--", "/usr/local/bin/pano-launcher"]
