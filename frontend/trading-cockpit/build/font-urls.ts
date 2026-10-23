import { resolve } from "node:path";
import type { Plugin } from "vite";

/** vinext normalizes cached CSS paths, but compares them to native Windows paths. */
export function fontUrls(): Plugin {
  let cachePrefix = "";
  let servedPrefix = "";
  return {
    name: "cockpit:font-urls",
    enforce: "post",
    configResolved(config) {
      cachePrefix = `${resolve(config.root, ".vinext", "fonts").replaceAll("\\", "/")}/`;
      const assetsDir = config.environments?.client?.build?.assetsDir ?? config.build.assetsDir;
      servedPrefix = `/${assetsDir || "_next/static"}/_vinext_fonts/`;
    },
    transform(code, id) {
      if (!cachePrefix || id.includes("node_modules") || !code.includes("selfHostedCSS") || !code.includes(cachePrefix)) return null;
      return { code: code.replaceAll(cachePrefix, servedPrefix), map: null };
    },
  };
}
