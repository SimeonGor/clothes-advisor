import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { readFileSync } from "node:fs";

export default defineConfig({
  base: "/app/",
  publicDir: false,
  plugins: [react(), {
    name: "bundle-onest-license",
    generateBundle() {
      this.emitFile({ type: "asset", fileName: "assets/Onest-OFL.txt",
        source: readFileSync(new URL("./src/assets/Onest-OFL.txt", import.meta.url)) });
    },
  }],
  server: {
    proxy: { "/api": process.env.VITE_API_PROXY || "http://127.0.0.1:8080" },
  },
  build: { outDir: "dist", emptyOutDir: true },
  test: { environment: "jsdom", setupFiles: ["./src/test-setup.ts"] },
});
