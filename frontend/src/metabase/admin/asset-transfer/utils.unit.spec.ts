import { parseBundle } from "./utils";

const bundle = {
  format: "metabase-asset-transfer",
  format_version: 1,
  producer: { metabase_version: "test", transfer_version: 1 },
  databases: [
    {
      id: 1,
      name: "Analytics",
      engine: "postgres",
      details: { host: "db", port: 5432 },
    },
  ],
  tables: [],
  fields: [],
  cards: [],
};

describe("asset transfer files", () => {
  it("accepts dashboards in version 2 and rejects missing or oversized dashboard lists", () => {
    const dashboardBundle = {
      ...bundle,
      format_version: 2,
      producer: { ...bundle.producer, transfer_version: 2 },
      dashboards: [{ entity_id: "dashboard", name: "Sales" }],
    };
    expect(parseBundle(JSON.stringify(dashboardBundle))).toEqual(
      dashboardBundle,
    );
    expect(() =>
      parseBundle(
        JSON.stringify({ ...dashboardBundle, dashboards: undefined }),
      ),
    ).toThrow();
    expect(() =>
      parseBundle(
        JSON.stringify({
          ...dashboardBundle,
          dashboards: Array.from(
            { length: 21 },
            () => dashboardBundle.dashboards[0],
          ),
        }),
      ),
    ).toThrow();
  });

  it("accepts a connection-only bundle", () => {
    expect(parseBundle(JSON.stringify(bundle))).toEqual(bundle);
  });

  it.each([
    "password",
    "ssh-tunnel-private-key",
    "connection-string",
    "jdbc-options",
    "unknown",
  ])("rejects %s before pre-filling a connection form", (key) => {
    expect(() =>
      parseBundle(
        JSON.stringify({
          ...bundle,
          databases: [{ ...bundle.databases[0], details: { [key]: "secret" } }],
        }),
      ),
    ).toThrow();
  });

  it("rejects a huge file and unsupported format", () => {
    expect(() => parseBundle(" ".repeat(10 * 1024 * 1024 + 1))).toThrow();
    expect(() =>
      parseBundle(JSON.stringify({ ...bundle, format_version: 2 })),
    ).toThrow();
  });
});
