/** Shared response-envelope types for tests, so route tests never need `as any`. */
export interface SuccessEnvelope<T> {
  schemaVersion: number;
  data: T;
}

export interface ErrorEnvelope {
  schemaVersion: number;
  error: { code: string; message: string };
}
