FROM node:24-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci

FROM node:24-alpine AS build
WORKDIR /app
ARG VITE_ADMIN_ENTRY_PATH=/sakuracianna
ARG VITE_ICP_NUMBER=ICP备案号待填写
ARG VITE_POLICE_RECORD_NUMBER=公安备案号待填写
ENV VITE_ADMIN_ENTRY_PATH=$VITE_ADMIN_ENTRY_PATH
ENV VITE_ICP_NUMBER=$VITE_ICP_NUMBER
ENV VITE_POLICE_RECORD_NUMBER=$VITE_POLICE_RECORD_NUMBER
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
RUN test -f /usr/share/nginx/html/index.html \
    && test -d /usr/share/nginx/html/assets \
    && find /usr/share/nginx/html/assets -type f | grep -q .

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
