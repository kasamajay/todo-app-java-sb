# Production image: compile + bundle the React app with Vite in a throwaway
# Node stage, then serve the static output from nginx. No Node process runs
# at runtime - see decisions/0013-production-mode-nginx-static-bundle.md.
# (Dev still uses Dockerfile.dev + the Vite dev server.)

FROM node:20 AS build

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Invoke vite's bin script directly via node, same as Dockerfile.dev -
# `npm run`/`npx` were observed to exit silently in this container context.
RUN node node_modules/vite/bin/vite.js build

FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
