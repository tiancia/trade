# Build from frontend/trading-cockpit; do not pass backend secrets as build args.
ARG NODE_IMAGE=node:22.23.3-bookworm-slim
FROM ${NODE_IMAGE} AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --include=dev --no-audit --no-fund
COPY app ./app
COPY public ./public
COPY build/font-urls.ts ./build/font-urls.ts
COPY next.config.ts postcss.config.mjs tsconfig.json ./
COPY vite.config.docker.ts ./vite.config.ts
RUN TRADE_DOCKER_STANDALONE=true node node_modules/vinext/dist/cli.js build

FROM ${NODE_IMAGE} AS runtime
ENV NODE_ENV=production \
    PORT=3000
WORKDIR /app
# Standalone output includes the server, assets, and required runtime packages.
COPY --from=build --chown=node:node /app/dist/standalone ./
USER node
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD node -e "fetch('http://127.0.0.1:3000/').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "server.js"]
