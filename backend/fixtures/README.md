# Fixtures provenance

- `slTransportDeparturesSlussen.sample.json` — trimmed from a live `GET https://transport.integration.sl.se/v1/sites/9192/departures` response fetched during architecture verification (2026-07-27). Kept as real, upstream-shaped data for contract/serialization tests.
- `slDeviationsSlussen.sample.json` — trimmed from a live `GET https://deviations.integration.sl.se/v1/messages?site=9192&future=true` response fetched the same day. Used to verify the site/line ID namespace match with SL Transport and to test disruption normalization.
- `slSites.sample.json` — the Slussen entry's shape (id, structure) matches the real `/v1/sites` schema verified live; several other entries (Fruängen, T-Centralen, Gullmarsplan, Radiohuset, etc.) are illustrative synthetic entries — correctly shaped, but their exact ids/coordinates are not asserted as precise live values. They exist only to give the search-ranking tests multiple realistic candidates.
