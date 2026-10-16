import vinext from "vinext";
import { defineConfig } from "vite";

// Node SSR deployment has no Cloudflare bindings or Sites hosting plugin.
// The gateway handles /api/ on the same origin; never bake backend credentials
// or a deployment-specific API URL into the browser bundle.
export default defineConfig({
  envDir: false,
  plugins: [vinext()],
  define: {
    "process.env.NEXT_PUBLIC_TRADE_API_URL": JSON.stringify(""),
  },
});
