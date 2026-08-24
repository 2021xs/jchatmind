import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

const backendTarget =
  process.env.VITE_JCHATMIND_DEV_PROXY_TARGET ?? "http://127.0.0.1:8080";

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      "/api": {
        target: backendTarget,
        changeOrigin: true,
      },
      "/sse": {
        target: backendTarget,
        changeOrigin: true,
      },
    },
  },
});
