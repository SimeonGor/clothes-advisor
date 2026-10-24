import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  base: "/app/",
  publicDir: false,
  plugins: [react()],
  server: {
    proxy: { "/api": process.env.VITE_API_PROXY || "http://127.0.0.1:8080" },
  },
  build: { outDir: "dist", emptyOutDir: true },
  test: { environment: "jsdom", setupFiles: ["./src/test-setup.ts"] },
});
