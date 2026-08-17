import { describe, expect, it } from "vitest";
import { deflateRawSync } from "node:zlib";
import { extractNamedFilesFromZip } from "../src/services/gtfsZipExtractor.js";

interface TestZipEntry {
  name: string;
  content: string;
  method: number; // 0 = STORED, 8 = DEFLATE, anything else = deliberately unsupported (for error-path tests)
}

/** Builds a real, valid (non-ZIP64) ZIP archive byte-for-byte from [entries] -- the exact same
 * Local File Header + Central Directory + End Of Central Directory structure
 * `gtfsZipExtractor.ts` reads, so these tests exercise the real format, not a mock of it. CRC-32
 * is deliberately left as 0 throughout (never checked by this reader -- see that module's own
 * doc); every other field is real. */
function buildZip(entries: TestZipEntry[]): Uint8Array {
  const localParts: Buffer[] = [];
  const centralParts: Buffer[] = [];
  let offset = 0;

  for (const entry of entries) {
    const nameBuf = Buffer.from(entry.name, "utf8");
    const rawContent = Buffer.from(entry.content, "utf8");
    const data = entry.method === 8 ? deflateRawSync(rawContent) : rawContent;
    const localHeaderOffset = offset;

    const localHeader = Buffer.alloc(30);
    localHeader.writeUInt32LE(0x04034b50, 0);
    localHeader.writeUInt16LE(20, 4);
    localHeader.writeUInt16LE(0, 6);
    localHeader.writeUInt16LE(entry.method, 8);
    localHeader.writeUInt16LE(0, 10);
    localHeader.writeUInt16LE(0, 12);
    localHeader.writeUInt32LE(0, 14);
    localHeader.writeUInt32LE(data.length, 18);
    localHeader.writeUInt32LE(rawContent.length, 22);
    localHeader.writeUInt16LE(nameBuf.length, 26);
    localHeader.writeUInt16LE(0, 28);

    localParts.push(localHeader, nameBuf, data);
    offset += localHeader.length + nameBuf.length + data.length;

    const centralHeader = Buffer.alloc(46);
    centralHeader.writeUInt32LE(0x02014b50, 0);
    centralHeader.writeUInt16LE(20, 4);
    centralHeader.writeUInt16LE(20, 6);
    centralHeader.writeUInt16LE(0, 8);
    centralHeader.writeUInt16LE(entry.method, 10);
    centralHeader.writeUInt16LE(0, 12);
    centralHeader.writeUInt16LE(0, 14);
    centralHeader.writeUInt32LE(0, 16);
    centralHeader.writeUInt32LE(data.length, 20);
    centralHeader.writeUInt32LE(rawContent.length, 24);
    centralHeader.writeUInt16LE(nameBuf.length, 28);
    centralHeader.writeUInt16LE(0, 30);
    centralHeader.writeUInt16LE(0, 32);
    centralHeader.writeUInt16LE(0, 34);
    centralHeader.writeUInt16LE(0, 36);
    centralHeader.writeUInt32LE(0, 38);
    centralHeader.writeUInt32LE(localHeaderOffset, 42);

    centralParts.push(centralHeader, nameBuf);
  }

  const centralDirectoryOffset = offset;
  const centralDirectoryBuf = Buffer.concat(centralParts);

  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(centralDirectoryBuf.length, 12);
  eocd.writeUInt32LE(centralDirectoryOffset, 16);
  eocd.writeUInt16LE(0, 20);

  return new Uint8Array(Buffer.concat([...localParts, centralDirectoryBuf, eocd]));
}

describe("extractNamedFilesFromZip", () => {
  it("extracts a STORED (uncompressed) entry", () => {
    const zip = buildZip([{ name: "routes.txt", content: "route_id,route_short_name\n1,11\n", method: 0 }]);
    expect(extractNamedFilesFromZip(zip, ["routes.txt"])).toEqual({ "routes.txt": "route_id,route_short_name\n1,11\n" });
  });

  it("extracts a DEFLATE-compressed entry", () => {
    const content = "trip_id,route_id\n".repeat(50) + "t1,1\n";
    const zip = buildZip([{ name: "trips.txt", content, method: 8 }]);
    expect(extractNamedFilesFromZip(zip, ["trips.txt"])).toEqual({ "trips.txt": content });
  });

  it("extracts multiple named entries, ignoring unrequested ones", () => {
    const zip = buildZip([
      { name: "routes.txt", content: "routes-content", method: 0 },
      { name: "trips.txt", content: "trips-content", method: 8 },
      { name: "agency.txt", content: "agency-content", method: 0 },
    ]);
    const result = extractNamedFilesFromZip(zip, ["routes.txt", "trips.txt"]);
    expect(result).toEqual({ "routes.txt": "routes-content", "trips.txt": "trips-content" });
    expect(result).not.toHaveProperty("agency.txt");
  });

  it("throws when a requested file is missing from the archive, naming every missing file", () => {
    const zip = buildZip([{ name: "routes.txt", content: "x", method: 0 }]);
    expect(() => extractNamedFilesFromZip(zip, ["routes.txt", "stop_times.txt", "trips.txt"])).toThrow(
      /missing required file\(s\): stop_times\.txt, trips\.txt/,
    );
  });

  it("throws a clear error for non-ZIP input", () => {
    const notAZip = new TextEncoder().encode("this is not a zip file at all");
    expect(() => extractNamedFilesFromZip(notAZip, ["routes.txt"])).toThrow(/not a valid zip/i);
  });

  it("throws a clear error for an unsupported compression method", () => {
    const zip = buildZip([{ name: "routes.txt", content: "x", method: 12 }]); // 12 = BZIP2, unsupported
    expect(() => extractNamedFilesFromZip(zip, ["routes.txt"])).toThrow(/unsupported zip compression method 12/i);
  });

  it("round-trips real GTFS-shaped CSV content byte for byte, including UTF-8 station names", () => {
    const routesContent = "route_id,route_short_name,route_type\n9011001000011000,11,401\n";
    const stopsContent = "stop_id,stop_name\n9091001000009340,Kungsträdgården\n";
    const zip = buildZip([
      { name: "routes.txt", content: routesContent, method: 8 },
      { name: "stops.txt", content: stopsContent, method: 8 },
    ]);
    const result = extractNamedFilesFromZip(zip, ["routes.txt", "stops.txt"]);
    expect(result["routes.txt"]).toBe(routesContent);
    expect(result["stops.txt"]).toBe(stopsContent);
  });

  it("handles an empty ZIP archive (zero entries) by reporting every requested file as missing", () => {
    const emptyZip = buildZip([]);
    expect(() => extractNamedFilesFromZip(emptyZip, ["routes.txt"])).toThrow(/missing required file\(s\): routes\.txt/);
  });
});
