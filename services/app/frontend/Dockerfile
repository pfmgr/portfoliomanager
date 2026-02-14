FROM node:25.3.0-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
RUN if ! grep -q "client_max_body_size" /etc/nginx/conf.d/default.conf; then \
    sed -i '1s/^/client_max_body_size 25m;\n/' /etc/nginx/conf.d/default.conf; \
  fi
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
