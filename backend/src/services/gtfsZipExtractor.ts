import { inflateRawSync } from "node:zlib";

/**
 * A minimal, dependency-free ZIP reader for exactly what GTFS Regional's own distribution format
 * needs (`GET https://opendata.samtrafiken.se/gtfs/{operator}/{operator}.zip` — an ordinary,
 * standard, non-encrypted ZIP archive): extract a small, named subset of entries
 * (`routes.txt`/`trips.txt`/`stop_times.txt`) from the archive's own Central Directory, entirely
 * in memory. Deliberately NOT a general-purpose ZIP library (no write support, no streaming
 * single-entry extraction, no ZIP64 — see this module's own "Scope and limits" doc below) and
 * deliberately NOT a new npm dependency: this backend had no ZIP-capable dependency already
 * installed, and the ZIP Central Directory format is simple and stable enough to implement
 * directly against Node's own built-in `node:zlib` (`inflateRawSync` for DEFLATE, the only
 * compression method beyond plain STORED that a standard zip tool ever produces) rather than
 * taking on a new, unverified supply-chain dependency for three files' worth of extraction.
 *
 * ## Format
 *
 * A ZIP archive's authoritative entry listing is its Central Directory, located via the End Of
 * Central Directory (EOCD) record — always the LAST thing in the file, but not at a fixed offset
 * from the end (a variable-length, usually-empty comment field follows it), so it must be found
 * by scanning backward for its signature. Each Central Directory entry records the entry's own
 * name, compression method, compressed/uncompressed size, and the byte offset of its OWN Local
 * File Header (a second, redundant per-entry header immediately preceding the entry's actual
 * compressed data) — extracting one entry means reading its Local File Header at that offset to
 * find exactly where the compressed data starts, then decompressing exactly `compressedSize`
 * bytes from there. All multi-byte integers in the ZIP format are little-endian.
 *
 * ## Scope and limits (deliberate, matching GTFS Regional's own real shape)
 *
 * - Only compression methods `0` (STORED — no compression) and `8` (DEFLATE — what every common
 *   zip tool, including the one that presumably built this feed, produces by default) are
 *   supported; any other method throws a clear, named error rather than silently misreading data.
 * - No ZIP64 support (the extension for archives/entries too large for the original format's own
 *   32-bit size/offset fields, needed only past ~4GB or 65,535 entries) — GTFS Regional's own
 *   per-operator feeds (SL's own network, not a nationwide feed) are documented as static daily
 *   snapshots of routes/trips/stop_times text, nowhere near that scale; a ZIP64 archive is
 *   detected and reported with a clear error rather than silently misread.
 * - No encryption, no multi-part/spanned archives — GTFS is public open data; neither applies.
 * - Everything happens on one in-memory `Buffer` built from the already-fully-downloaded response
 *   body — appropriate for Vercel's own request/response lifecycle (no filesystem access assumed
 *   or required, no temp file ever written) and for a feed of this documented size. This is NOT
 *   suitable for a truly large (100MB+) archive processed under tight memory limits; if GTFS
 *   Regional's real SL feed turns out to be that large, this would need revisiting — but nothing
 *   in Trafiklab's own documentation suggests it is (see `lineTopologyDirectory.ts`'s own doc for
 *   this feature's actual measured/estimated sizes once real data can be obtained).
 */

const END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50;
const CENTRAL_DIRECTORY_FILE_HEADER_SIGNATURE = 0x02014b50;
const LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50;
const ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE = 0x07064b50;

const EOCD_FIXED_SIZE = 22;
const MAX_ZIP_COMMENT_LENGTH = 65535;

interface CentralDirectoryEntry {
  name: string;
  compressionMethod: number;
  compressedSize: number;
  localHeaderOffset: number;
}

/** Scans backward from the end of [buf] for the End Of Central Directory signature — it is
 * always the last fixed-size record in a well-formed ZIP, but a variable-length comment (0 to
 * 65,535 bytes) may follow the signature's own 22-byte fixed record before the true end of file,
 * so a fixed offset from the end cannot be assumed. */
function findEndOfCentralDirectory(buf: Buffer): number {
  const searchFloor = Math.max(0, buf.length - EOCD_FIXED_SIZE - MAX_ZIP_COMMENT_LENGTH);
  for (let offset = buf.length - EOCD_FIXED_SIZE; offset >= searchFloor; offset--) {
    if (buf.readUInt32LE(offset) === END_OF_CENTRAL_DIRECTORY_SIGNATURE) return offset;
  }
  throw new Error("Not a valid ZIP archive (End Of Central Directory record not found)");
}

function readCentralDirectory(buf: Buffer): CentralDirectoryEntry[] {
  const eocdOffset = findEndOfCentralDirectory(buf);
  const totalEntries = buf.readUInt16LE(eocdOffset + 10);
  const centralDirectorySize = buf.readUInt32LE(eocdOffset + 12);
  const centralDirectoryOffset = buf.readUInt32LE(eocdOffset + 16);

  // ZIP64 marks its "real" values as 0xFFFF/0xFFFFFFFF sentinels in the ordinary EOCD record,
  // with the true values in a separate ZIP64 EOCD record this reader does not parse — detect the
  // sentinel (or the ZIP64 locator record that always immediately precedes a ZIP64 EOCD) and fail
  // with a clear, named error rather than silently reading truncated/wrong data.
  const looksLikeZip64 =
    totalEntries === 0xffff ||
    centralDirectoryOffset === 0xffffffff ||
    (eocdOffset >= 20 && buf.readUInt32LE(eocdOffset - 20) === ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE);
  if (looksLikeZip64) {
    throw new Error("ZIP64 archives are not supported by this reader (see gtfsZipExtractor.ts's own 'Scope and limits' doc)");
  }

  const entries: CentralDirectoryEntry[] = [];
  let offset = centralDirectoryOffset;
  const centralDirectoryEnd = centralDirectoryOffset + centralDirectorySize;
  for (let i = 0; i < totalEntries; i++) {
    if (offset + 46 > centralDirectoryEnd || buf.readUInt32LE(offset) !== CENTRAL_DIRECTORY_FILE_HEADER_SIGNATURE) {
      throw new Error(`Malformed ZIP Central Directory (entry ${i} of ${totalEntries} at offset ${offset})`);
    }
    const compressionMethod = buf.readUInt16LE(offset + 10);
    const compressedSize = buf.readUInt32LE(offset + 20);
    const nameLength = buf.readUInt16LE(offset + 28);
    const extraLength = buf.readUInt16LE(offset + 30);
    const commentLength = buf.readUInt16LE(offset + 32);
    const localHeaderOffset = buf.readUInt32LE(offset + 42);
    const name = buf.toString("utf8", offset + 46, offset + 46 + nameLength);
    entries.push({ name, compressionMethod, compressedSize, localHeaderOffset });
    offset += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}

function extractEntryText(buf: Buffer, entry: CentralDirectoryEntry): string {
  const offset = entry.localHeaderOffset;
  if (offset + 30 > buf.length || buf.readUInt32LE(offset) !== LOCAL_FILE_HEADER_SIGNATURE) {
    throw new Error(`Malformed ZIP Local File Header for "${entry.name}"`);
  }
  const nameLength = buf.readUInt16LE(offset + 26);
  const extraLength = buf.readUInt16LE(offset + 28);
  const dataStart = offset + 30 + nameLength + extraLength;
  const dataEnd = dataStart + entry.compressedSize;
  if (dataEnd > buf.length) {
    throw new Error(`Truncated ZIP entry data for "${entry.name}"`);
  }
  const compressedData = buf.subarray(dataStart, dataEnd);

  if (entry.compressionMethod === 0) return compressedData.toString("utf8");
  if (entry.compressionMethod === 8) return inflateRawSync(compressedData).toString("utf8");
  throw new Error(`Unsupported ZIP compression method ${entry.compressionMethod} for "${entry.name}" (only STORED and DEFLATE are supported)`);
}

/**
 * Extracts exactly [fileNames] from [zipBytes] (an already-fully-downloaded ZIP archive, e.g. via
 * `Response.arrayBuffer()`) and returns each one's own decoded UTF-8 text, keyed by its exact
 * name as it appears in the archive. Throws (never returns a partial result) if the archive is
 * malformed, uses an unsupported feature (see this module's own "Scope and limits" doc), or is
 * missing any one of [fileNames] — GTFS's own required files are not optional for this feature's
 * own purposes, so a caller should treat this as a proper feed-unavailable failure, exactly like
 * a network error, never silently proceed with a subset.
 */
export function extractNamedFilesFromZip(zipBytes: Uint8Array, fileNames: readonly string[]): Record<string, string> {
  const buf = Buffer.from(zipBytes.buffer, zipBytes.byteOffset, zipBytes.byteLength);
  const entries = readCentralDirectory(buf);
  const byName = new Map(entries.map((e) => [e.name, e] as const));

  const result: Record<string, string> = {};
  const missing: string[] = [];
  for (const fileName of fileNames) {
    const entry = byName.get(fileName);
    if (entry == null) {
      missing.push(fileName);
      continue;
    }
    result[fileName] = extractEntryText(buf, entry);
  }
  if (missing.length > 0) {
    throw new Error(`GTFS zip is missing required file(s): ${missing.join(", ")}`);
  }
  return result;
}
