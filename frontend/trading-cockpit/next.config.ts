import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  ...(process.env.TRADE_DOCKER_STANDALONE === "true"
    ? { output: "standalone" }
    : {}),
};

export default nextConfig;
