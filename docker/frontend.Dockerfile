FROM node:20-alpine AS build
ARG APP
WORKDIR /app
COPY ${APP}/package*.json ./
RUN --mount=type=cache,target=/root/.npm \
    sed -i 's|https://registry.npmmirror.com/|https://registry.npmjs.org/|g' package-lock.json \
    && npm ci --no-audit --no-fund
COPY ${APP}/ ./
ENV VITE_API_BASE_URL=/api
ENV VITE_IMAGE_API_BASE_URL=/api
RUN npm run build

FROM nginx:1.27-alpine
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
