import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Inside Docker Compose, this dev server runs *inside* the `web` container,
// so `localhost` would mean the web container itself, not the api
// container. Compose wires VITE_API_PROXY_TARGET=http://api:8080 (Docker's
// service-name DNS) as an env var on the web service; the localhost
// fallback below only applies if you run `npm run dev` directly on the
// host against a locally-running api process.
const apiProxyTarget = process.env.VITE_API_PROXY_TARGET || 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    port: 5173,
    proxy: {
      '/api': {
        target: apiProxyTarget,
        changeOrigin: true,
      },
    },
    // Docker Desktop's Windows bind mount doesn't reliably propagate file
    // change events into the container, so HMR falls back to polling.
    watch: {
      usePolling: true,
      interval: 500,
    },
    // Vite rejects requests whose Host header it doesn't recognize
    // ("Blocked request. This host is not allowed") as DNS-rebinding
    // protection. A request tunneled through ngrok arrives with
    // Host: <random>.ngrok-free.app, not localhost:5173, so its domains
    // need to be allowed explicitly - see decisions/0009-ngrok-for-public-exposure.md.
    allowedHosts: ['.ngrok-free.app', '.ngrok-free.dev', '.ngrok.app', '.ngrok.io', '.ngrok.dev'],
  },
})
