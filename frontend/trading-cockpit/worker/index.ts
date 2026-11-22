/** Cloudflare Worker entry point for Orbit Trading Cockpit. */
import { handleImageOptimization, DEFAULT_DEVICE_SIZES, DEFAULT_IMAGE_SIZES } from "vinext/server/image-optimization";
import handler from "vinext/server/app-router-entry";

interface Env {
  ASSETS: { fetch(request: Request): Promise<Response> };
  /** Optional runtime backend origin used by deployed Sites builds. */
  TRADE_API_URL?: string;
  IMAGES: {
    input(stream: ReadableStream): {
      transform(options: Record<string, unknown>): {
        output(options: { format: string; quality: number }): Promise<{ response(): Response }>;
      };
    };
  };
}

interface ExecutionContext {
  waitUntil(promise: Promise<unknown>): void;
  passThroughOnException(): void;
}

// Image security config. SVG sources with .svg extension auto-skip the
// optimization endpoint on the client side (served directly, no proxy).
// To route SVGs through the optimizer (with security headers), set
// dangerouslyAllowSVG: true in next.config.js and uncomment below:
// const imageConfig: ImageConfig = { dangerouslyAllowSVG: true };

const worker = {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/_vinext/image") {
      const allowedWidths = [...DEFAULT_DEVICE_SIZES, ...DEFAULT_IMAGE_SIZES];
      return handleImageOptimization(request, {
        fetchAsset: (path) => env.ASSETS.fetch(new Request(new URL(path, request.url))),
        transformImage: async (body, { width, format, quality }) => {
          const result = await env.IMAGES.input(body).transform(width > 0 ? { width } : {}).output({ format, quality });
          return result.response();
        },
      }, allowedWidths);
    }

    // Both the API and the page renderer derive public URLs from these headers.
    const headers = new Headers(request.headers);
    for (const name of [...headers.keys()]) {
      if (name === "forwarded" || name.startsWith("x-forwarded-") || name === "x-real-ip") {
        headers.delete(name);
      }
    }
    headers.set("x-forwarded-host", url.host);
    headers.set("x-forwarded-proto", url.protocol.slice(0, -1));
    headers.set("x-forwarded-port", url.port || (url.protocol === "https:" ? "443" : "80"));

    if (url.pathname.startsWith("/api/") && env.TRADE_API_URL) {
      const upstream = new URL(env.TRADE_API_URL);
      upstream.pathname = `${upstream.pathname.replace(/\/$/, "")}${url.pathname}`;
      upstream.search = url.search;
      headers.delete("host");
      return fetch(upstream, {
        method: request.method,
        headers,
        body: request.method === "GET" || request.method === "HEAD" ? null : request.body,
        redirect: "manual",
      });
    }

    headers.set("host", url.host);
    return handler.fetch(new Request(request, { headers }), env, ctx);
  },
};

export default worker;
