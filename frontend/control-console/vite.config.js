import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "CONTROL_");
  const proxy = {
    "/api": {
      target: env.CONTROL_API_URL || "http://127.0.0.1:8080",
      // Preserve browser Origin and Host for the backend's same-origin CSRF check.
      changeOrigin: false,
    },
  };
  return { plugins: [react()], server: { proxy }, preview: { proxy } };
});
