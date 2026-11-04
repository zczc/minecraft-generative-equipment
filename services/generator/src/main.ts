import { createApp } from "./app.js";
import { loadConfig } from "./config.js";
const config = loadConfig();
const { app } = await createApp(config);
for (const signal of ["SIGTERM", "SIGINT"] as const)
  process.once(signal, () => {
    void app.close().catch(() => {
      process.exitCode = 1;
    });
  });
await app.listen({ host: "127.0.0.1", port: config.port });
