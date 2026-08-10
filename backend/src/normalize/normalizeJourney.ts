import type { RawJourneyPlannerJourney } from "../services/slJourneyPlannerClient.js";

function modeFor(productClass?: number, name?: string): string {
  if (productClass === 0 || productClass === 1) return "TRAIN";
  if (productClass === 2) return "METRO";
  if (productClass === 3 || productClass === 4) return "TRAM";
  if (productClass === 5 || productClass === 6 || productClass === 7 || productClass === 10 || productClass === 19) return "BUS";
  if (productClass === 9) return "FERRY";
  if (productClass === 99) return "WALK";
  const value = name?.toUpperCase() ?? "UNKNOWN";
  if (value.includes("FOOTPATH") || value.includes("WALK")) return "WALK";
  if (value.includes("BUS")) return "BUS";
  if (value.includes("TUNNEL") || value.includes("METRO")) return "METRO";
  if (value.includes("PENDEL") || value.includes("TÅG") || value.includes("TRAIN")) return "TRAIN";
  if (value.includes("SPÅR") || value.includes("TRAM")) return "TRAM";
  if (value.includes("BÅT") || value.includes("FERRY") || value.includes("SHIP")) return "FERRY";
  return "UNKNOWN";
}

function preferredTime(estimated?: string, planned?: string): string | undefined {
  return estimated ?? planned;
}

function disruptionText(infos: unknown[] | undefined): string[] {
  return (infos ?? []).flatMap((info) => {
  if (typeof info === "string") return [info];
  if (info && typeof info === "object") {
    const record = info as Record<string, unknown>;
    return [record.content, record.text, record.subtitle, record.name].filter((v): v is string => typeof v === "string");
  }
  return [];
  });
}

export function normalizeJourney(raw: RawJourneyPlannerJourney) {
  // Walking/transfer legs can still carry a transportation object. Select the first
  // recognized public-transport leg, not merely the first object-shaped leg.
  const transitLegs = raw.legs.filter((leg) => {
    const mode = modeFor(leg.transportation?.product?.class, leg.transportation?.product?.name);
    return mode !== "UNKNOWN" && mode !== "WALK";
  });
  const first = transitLegs[0] ?? raw.legs[0]!;
  const last = raw.legs[raw.legs.length - 1]!;
  const departureTime = preferredTime(first.origin.departureTimeEstimated, first.origin.departureTimePlanned);
  const arrivalTime = preferredTime(last.destination.arrivalTimeEstimated, last.destination.arrivalTimePlanned);
  const legTripIds = raw.legs.flatMap((leg) => leg.properties?.tripId == null ? [] : [leg.properties.tripId]);
  const journeyId = raw.tripId ?? [...new Set(legTripIds)].join(":");
  if (!journeyId || !departureTime || !arrivalTime) return undefined;
  return {
    journeyId,
    originName: first.origin.name,
    destinationName: last.destination.name,
    departureTime,
    arrivalTime,
    transferCount: raw.interchanges ?? 0,
    firstLeg: {
      transportMode: modeFor(first.transportation?.product?.class, first.transportation?.product?.name),
      lineDesignation: first.transportation?.disassembledName ?? first.transportation?.number ?? null,
      direction: first.transportation?.destination?.name ?? null,
      originName: first.origin.name,
      destinationName: first.destination.name,
      departureTime,
      arrivalTime: preferredTime(first.destination.arrivalTimeEstimated, first.destination.arrivalTimePlanned) ?? arrivalTime,
      isRealtime: first.origin.departureTimeEstimated != null,
    },
    legs: raw.legs.map((leg) => ({
      transportMode: leg.transportation == null ? "WALK" : modeFor(leg.transportation.product?.class, leg.transportation.product?.name),
      lineDesignation: leg.transportation?.disassembledName ?? leg.transportation?.number ?? null,
      direction: leg.transportation?.destination?.name ?? null,
      originName: leg.origin.name,
      destinationName: leg.destination.name,
      departureTime: preferredTime(leg.origin.departureTimeEstimated, leg.origin.departureTimePlanned) ?? null,
      arrivalTime: preferredTime(leg.destination.arrivalTimeEstimated, leg.destination.arrivalTimePlanned) ?? null,
      isRealtime: leg.origin.departureTimeEstimated != null || leg.destination.arrivalTimeEstimated != null,
      disruptions: disruptionText(leg.infos),
    })),
    disruptions: raw.legs.flatMap((leg) => disruptionText(leg.infos)),
  };
}
