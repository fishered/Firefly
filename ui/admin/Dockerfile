FROM node:22-alpine

ARG FIREFLY_VERSION=1.1.0
LABEL org.opencontainers.image.title="Firefly Admin UI" \
      org.opencontainers.image.description="Web console and API proxy for Firefly" \
      org.opencontainers.image.version="${FIREFLY_VERSION}"

WORKDIR /opt/firefly-admin

COPY --chown=node:node package.json server.mjs ./
COPY --chown=node:node public ./public

ENV NODE_ENV=production \
    FIREFLY_ADMIN_UI_HOST=0.0.0.0 \
    FIREFLY_ADMIN_UI_PORT=9720 \
    FIREFLY_ADMIN_API=http://firefly-server:9710

EXPOSE 9720

HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=6 \
    CMD node -e "fetch('http://127.0.0.1:9720/ui/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

USER node

CMD ["node", "server.mjs"]
