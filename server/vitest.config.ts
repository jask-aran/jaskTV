import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig(async () => {
  const migrations = await readD1Migrations("./migrations");
  return {
    plugins: [
      cloudflareTest({
        wrangler: { configPath: "./wrangler.jsonc" },
        // Tests sign in with "dev:" tokens; this switch exists only here and in a local .dev.vars, never in a deployed config.
        miniflare: { bindings: { TEST_MIGRATIONS: migrations, DEV_AUTH: "1" } },
      }),
    ],
    test: { setupFiles: ["./test/setup.ts"] },
  };
});
