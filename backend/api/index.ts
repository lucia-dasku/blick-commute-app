/**
 * Vercel entry point. Per Hono's current Vercel guidance (hono.dev/docs/getting-started/vercel),
 * Vercel's Node.js runtime accepts a Hono app's default export directly — no separate
 * Vercel adapter package is required. `src/app.ts` is shared with the local dev server
 * (`src/server.ts`) so routes/services are identical between the two entry points.
 */
import { createApp } from "../src/app.js";

const app = createApp();

export default app;
