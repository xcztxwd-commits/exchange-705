FROM dockerproxy.net/library/tomcat@sha256:d6a7d58a769d13229712cb8fcb4b4beea8e35afde97c34fe42532b4567756294 AS tools
ARG NODE_VERSION=20.20.2
ARG PLAYWRIGHT_VERSION=1.62.1
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/browser/browsers
# Only this independent fixture image; TLS and Ubuntu archive signature verification stay enabled.
RUN sed -i 's@http://archive.ubuntu.com/ubuntu@https://mirrors.tuna.tsinghua.edu.cn/ubuntu@g; s@http://security.ubuntu.com/ubuntu@https://mirrors.tuna.tsinghua.edu.cn/ubuntu@g; s@Components: main restricted universe multiverse@Components: main universe@g' /etc/apt/sources.list.d/ubuntu.sources \
    && printf 'Acquire::https::Timeout "30";\nAcquire::Retries "1";\n' > /etc/apt/apt.conf.d/99-stage1-download-timeout \
    && apt-get update && apt-get install -y --no-install-recommends ca-certificates curl xz-utils libnss3-tools
RUN curl -fL --connect-timeout 30 --max-time 300 --retry 1 "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.xz" -o /tmp/node.tar.xz \
    && curl -fL --connect-timeout 30 --max-time 60 "https://nodejs.org/dist/v${NODE_VERSION}/SHASUMS256.txt" -o /tmp/node-sha.txt \
    && grep " node-v${NODE_VERSION}-linux-x64.tar.xz$" /tmp/node-sha.txt | sed 's@node-v[^ ]*-linux-x64.tar.xz@/tmp/node.tar.xz@' | sha256sum -c - \
    && tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 \
    && mkdir -p /opt/browser && cd /opt/browser \
    && PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm install --ignore-scripts --omit=dev --save-exact "playwright@${PLAYWRIGHT_VERSION}" \
    && rm -f /tmp/node.tar.xz /tmp/node-sha.txt
FROM tools AS browser
COPY --from=fixtureassets /debs/ /var/cache/apt/archives/
COPY --from=fixtureassets /browsers/ /opt/browser/browsers/
COPY --from=fixtureassets /plan.json /opt/browser/offline-plan.json
RUN node -e 'const p=require("/opt/browser/offline-plan.json"),s=require("child_process").spawnSync("apt-get",["--no-download","--yes","--no-install-recommends","install",...p.packages],{stdio:"inherit"});process.exit(s.status??1)' \
    && cd /opt/browser && ./node_modules/.bin/playwright install-deps --dry-run chromium \
    && useradd --create-home --uid 1001 --shell /bin/sh pwuser \
    && rm -rf /var/lib/apt/lists/* /root/.npm /var/cache/apt/archives/*.deb \
    && chmod -R a+rX /opt/browser \
    && chmod 755 /opt/browser/browsers/chromium_headless_shell-1234/chrome-headless-shell-linux64/chrome-headless-shell /opt/browser/browsers/ffmpeg-1011/ffmpeg-linux
COPY entrypoint.sh /opt/browser/entrypoint.sh
RUN chmod 755 /opt/browser/entrypoint.sh
WORKDIR /workspace/705
ENTRYPOINT ["/opt/browser/entrypoint.sh"]
