# Build from frontend/x-console. No account tokens belong in this image.
ARG NODE_IMAGE=node:22.23.3-bookworm-slim
ARG NGINX_IMAGE=nginx:1.30.6-alpine
FROM ${NODE_IMAGE} AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --include=dev --no-audit --no-fund
COPY index.html vite.config.docker.js ./
COPY src ./src
RUN npm exec vite -- build --config vite.config.docker.js

FROM ${NGINX_IMAGE} AS runtime
COPY --chmod=0644 nginx.conf /etc/nginx/nginx.conf
COPY --from=build /app/dist /usr/share/nginx/html
USER nginx
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["nginx"]
CMD ["-g", "daemon off;"]
