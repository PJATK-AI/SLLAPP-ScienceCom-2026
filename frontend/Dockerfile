FROM node:24-bookworm-slim AS build
WORKDIR /workspace

RUN npm install --global pnpm@12.21.0
COPY . .
RUN pnpm install --frozen-lockfile

ENV VITE_API_BASE_URL=/api \
    VITE_WS_URL=/ws
RUN pnpm prepare:camera && pnpm build

FROM nginx:stable-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/apps/web/dist/ /usr/share/nginx/html/
