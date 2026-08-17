/**
 * Minimal, focused CSV parsing for exactly the three GTFS static files this feature needs
 * (`routes.txt`, `trips.txt`, `stop_times.txt`) — not a general CSV library, not a GTFS SDK. See
 * `services/lineTopologyDirectory.ts` for how these are combined with the GTFS-stop-id-to-
 * StopArea identity bridge and `domain/lineTopologyGraph.ts`'s graph builder.
 *
 * GTFS static files are ordinary RFC 4180-shaped CSV: a header row naming every column, quoted
 * fields for values containing a comma/quote/newline, `""` as an escaped quote inside a quoted
 * field. Real GTFS values for the fields this backend actually reads (ids, short names, integer
 * codes) are never quoted in practice, but the parser below still handles quoting correctly
 * rather than assuming it never occurs.
 */

/** Splits [text] into rows of raw string fields, honoring RFC 4180 quoting. Blank trailing lines
 * (a file ending in a newline) are dropped; a genuinely blank row in the middle of the file
 * (two consecutive newlines) is also dropped, since GTFS never uses blank lines meaningfully. */
function parseCsvRows(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let inQuotes = false;
  const normalized = text.replace(/\r\n/g, "\n").replace(/\r/g, "\n");

  const endField = () => {
    row.push(field);
    field = "";
  };
  const endRow = () => {
    endField();
    if (row.some((f) => f.length > 0)) rows.push(row);
    row = [];
  };

  for (let i = 0; i < normalized.length; i++) {
    const char = normalized[i]!;
    if (inQuotes) {
      if (char === '"') {
        if (normalized[i + 1] === '"') {
          field += '"';
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        field += char;
      }
      continue;
    }
    if (char === '"') {
      inQuotes = true;
    } else if (char === ",") {
      endField();
    } else if (char === "\n") {
      endRow();
    } else {
      field += char;
    }
  }
  if (field.length > 0 || row.length > 0) endRow();
  return rows;
}

/** Parses [text] into an array of header-keyed records — the generic building block every
 * typed extractor below uses. A row shorter than the header is padded with empty strings for
 * its missing trailing columns (real GTFS occasionally omits trailing optional columns) rather
 * than throwing; a row longer than the header has its extra fields silently dropped, matching
 * ordinary permissive CSV consumption. */
export function parseGtfsCsv(text: string): Array<Record<string, string>> {
  const rows = parseCsvRows(text);
  if (rows.length === 0) return [];
  const header = rows[0]!.map((h) => h.trim());
  return rows.slice(1).map((row) => {
    const record: Record<string, string> = {};
    header.forEach((column, index) => {
      record[column] = row[index] ?? "";
    });
    return record;
  });
}

/**
 * One `routes.txt` row this backend actually needs — `stop_id`/`route_id`/`trip_id` are GTFS
 * identifiers and stay plain strings end to end (see this module's own top-level doc, and
 * `services/lineTopologyDirectory.ts`'s own doc on why: they are never proven to fit in a JS
 * safe integer, unlike SL Transport's own StopArea/site ids). `routeType` is kept as GTFS's own
 * raw numeric `route_type` code, unrelated to and never conflated with this backend's own
 * `TransportMode` strings here — Trafiklab's real feeds use the EXTENDED `route_type` vocabulary,
 * not GTFS's basic 0–7 scheme (confirmed live against Trafiklab's own documentation); see
 * `transportModeForGtfsRouteType` in `lineTopologyDirectory.ts` for the one place that
 * evidence-backed translation happens.
 */
export interface GtfsRoute {
  routeId: string;
  shortName: string;
  routeType: number;
}

export interface GtfsTrip {
  tripId: string;
  routeId: string;
}

export interface GtfsStopTime {
  tripId: string;
  stopId: string;
  stopSequence: number;
}

export function parseGtfsRoutes(text: string): GtfsRoute[] {
  return parseGtfsCsv(text)
    .map((r) => ({ routeId: r.route_id ?? "", shortName: r.route_short_name ?? "", routeType: Number(r.route_type) }))
    .filter((r) => r.routeId.length > 0 && Number.isFinite(r.routeType));
}

export function parseGtfsTrips(text: string): GtfsTrip[] {
  return parseGtfsCsv(text)
    .map((r) => ({ tripId: r.trip_id ?? "", routeId: r.route_id ?? "" }))
    .filter((t) => t.tripId.length > 0 && t.routeId.length > 0);
}

/** `stopSequence` is a small, GTFS-spec-mandated non-negative integer (safe to coerce to `number`
 * — unlike `stopId`, which stays a string; see `GtfsStopTime`'s own doc). A row with a
 * non-numeric `stop_sequence` is dropped rather than silently sorting as `NaN`. */
export function parseGtfsStopTimes(text: string): GtfsStopTime[] {
  return parseGtfsCsv(text)
    .map((r) => ({ tripId: r.trip_id ?? "", stopId: r.stop_id ?? "", stopSequence: Number(r.stop_sequence) }))
    .filter((s) => s.tripId.length > 0 && s.stopId.length > 0 && Number.isFinite(s.stopSequence));
}
