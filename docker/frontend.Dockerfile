FROM node:20-alpine AS build
ARG APP
WORKDIR /app
COPY ${APP}/package*.json ./
RUN --mount=type=cache,target=/root/.npm \
    sed -i 's|https://registry.npmmirror.com/|https://registry.npmjs.org/|g' package-lock.json \
    && npm ci --no-audit --no-fund
COPY ${APP}/ ./
COPY docker/device-layout.js /tmp/device-layout.js
ENV VITE_API_BASE_URL=/api
ENV VITE_IMAGE_API_BASE_URL=/api
RUN if [ "$APP" = "exchange-frontend" ]; then npm run build -- --base=/mobile/; else npm run build; fi \
    && if [ "$APP" = "exchange-pc" ] || [ "$APP" = "exchange-frontend" ]; then \
      layout=pc; [ "$APP" != "exchange-frontend" ] || layout=mobile; \
      cp /tmp/device-layout.js dist/device-layout.js; \
      sed -i "s|</head>|<script src=\"/device-layout.js\" data-layout=\"$layout\"></script></head>|" dist/index.html; \
    fi

FROM nginx:1.27-alpine
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
