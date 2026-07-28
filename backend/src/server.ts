import { serve } from "@hono/node-server";
import { createApp } from "./app.js";
import { config } from "./config/env.js";

const app = createApp();

serve({ fetch: app.fetch, port: config.port }, (info) => {
  console.log(`Blick backend listening on http://localhost:${info.port}/api/v1`);
});
