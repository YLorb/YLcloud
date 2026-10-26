FROM node:22-alpine AS build

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY trusted-proxy.conf /etc/nginx/trusted-proxy.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
