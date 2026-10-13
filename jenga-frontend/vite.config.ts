import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { VitePWA } from "vite-plugin-pwa";

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    VitePWA({
      registerType: "prompt",
      includeAssets: ["icons/*.png"],
      workbox: {
        // App shell only. Balances and transfers are never cached.
        globPatterns: ["**/*.{js,css,html,woff,woff2,png,svg}"],
        navigateFallback: "/index.html",
        navigateFallbackDenylist: [/^\/api\//, /^\/healthz/, /^\/readyz/, /^\/openapi\.json/, /^\/swagger-ui/],
        runtimeCaching: [],
        cleanupOutdatedCaches: true,
      },
      manifest: {
        name: "JengaBank Payments",
        short_name: "JengaBank",
        description: "Send money and manage payments at JengaBank.",
        theme_color: "#1B2A2F",
        background_color: "#EDEFEA",
        display: "standalone",
        start_url: "/",
        scope: "/",
        icons: [
          { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png" },
          { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png" },
          { src: "/icons/icon-maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
        ],
      },
    }),
  ],
  server: {
    proxy: {
      "/api": "http://localhost:8080",
    },
  },
  build: {
    sourcemap: false,
  },
});
