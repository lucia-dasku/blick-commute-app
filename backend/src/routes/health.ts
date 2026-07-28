import { Hono } from "hono";
import { successEnvelope } from "../models/common.js";

export const healthRoute = new Hono();

healthRoute.get("/", (c) => {
  c.header("Cache-Control", "no-store");
  return c.json(successEnvelope({ status: "ok" as const, timestamp: new Date().toISOString() }));
});
