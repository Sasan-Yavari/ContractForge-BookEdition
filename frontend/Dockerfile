FROM node:22-bookworm AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ .
COPY contracts/ /contracts/
RUN npm run generate:api && npm run build

FROM ghcr.io/nginx/nginx-unprivileged:1.30.5-alpine3.24-slim@sha256:7f79f463c6eb0d0bf8782992bb9636434398e3e5848398805aef2c9b025d8609
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY frontend/security-headers.conf /etc/nginx/security-headers.conf
COPY frontend/docker-content-security-policy.sh /docker-entrypoint.d/30-content-security-policy.sh
COPY frontend/docker-entrypoint.sh /docker-entrypoint.d/40-runtime-config.sh
USER root
RUN apk add --no-cache 'zlib=1.3.2-r1' \
    && chmod 755 /docker-entrypoint.d/30-content-security-policy.sh /docker-entrypoint.d/40-runtime-config.sh \
    && sed -i '/include \/etc\/nginx\/conf.d\/\*\.conf;/i\    include /tmp/contractforge-content-security-policy.conf;' /etc/nginx/nginx.conf \
    && chmod 1777 /tmp \
    && grep -Fq 'include /tmp/contractforge-content-security-policy.conf;' /etc/nginx/nginx.conf
COPY --from=build /app/dist /usr/share/nginx/html
VOLUME ["/tmp"]
USER 101:101
EXPOSE 8080
