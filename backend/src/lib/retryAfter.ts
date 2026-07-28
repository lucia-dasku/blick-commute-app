/**
 * Validates a `Retry-After` header value per RFC 9110 §10.2.3: either `delay-seconds`
 * (a non-negative integer number of seconds, no sign, no decimals) or an HTTP-date
 * (IMF-fixdate, e.g. `"Wed, 21 Oct 2026 07:28:00 GMT"`). An upstream sending anything
 * else — negative, non-numeric, a non-HTTP-date string, or otherwise malformed — must
 * not have that value blindly forwarded to this backend's own clients (see
 * src/lib/upstreamFetch.ts).
 */
const IMF_FIXDATE_RE =
  /^(Mon|Tue|Wed|Thu|Fri|Sat|Sun), \d{2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \d{4} \d{2}:\d{2}:\d{2} GMT$/;

export function isValidRetryAfterValue(value: string): boolean {
  if (/^\d+$/.test(value)) {
    // delay-seconds: an unsigned integer. No leading '+'/'-', no decimal point.
    return true;
  }
  if (IMF_FIXDATE_RE.test(value)) {
    return !Number.isNaN(Date.parse(value));
  }
  return false;
}
