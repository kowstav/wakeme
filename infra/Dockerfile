# Wake Me API and worker image. One image, two processes:
#   API:    node services/api/src/entrypoints/api.ts
#   Worker: node services/api/src/entrypoints/worker.ts
#   Migrate (one-off, migration credentials): node services/api/src/entrypoints/migrate.ts
# Node 24 runs the TypeScript sources directly (type stripping); CI type-checks them.
# Before production: pin the base image by digest and generate an SBOM for the build.

FROM node:24-bookworm-slim AS deps
WORKDIR /app
ENV NODE_ENV=production
COPY package.json package-lock.json ./
COPY packages/policy/package.json packages/policy/
COPY packages/challenges/package.json packages/challenges/
COPY services/api/package.json services/api/
RUN npm ci --omit=dev --workspace @wakeme/api --include-workspace-root=false --ignore-scripts --no-audit --no-fund

FROM node:24-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production
RUN apt-get update && apt-get install -y --no-install-recommends tini && rm -rf /var/lib/apt/lists/*
COPY --from=deps /app/node_modules ./node_modules
COPY package.json ./
COPY packages/policy/package.json packages/policy/
COPY packages/policy/src packages/policy/src
COPY packages/challenges/package.json packages/challenges/
COPY packages/challenges/src packages/challenges/src
COPY packages/challenges/schema packages/challenges/schema
COPY services/api/package.json services/api/
COPY services/api/src services/api/src
COPY services/api/migrations services/api/migrations
USER node
EXPOSE 8080
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["node", "services/api/src/entrypoints/api.ts"]
HEALTHCHECK --interval=30s --timeout=3s CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8080)+'/healthz').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
