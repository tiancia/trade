import { defineConfig } from 'vite';

// Same-origin API routing belongs to the outer gateway in production.
export default defineConfig({ envDir: false });
