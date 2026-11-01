import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Production API routing belongs to the authenticated gateway.
export default defineConfig({ envDir: false, plugins: [react()] });
