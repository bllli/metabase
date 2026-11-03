import { MAX_BUNDLE_BYTES, checkFileSize } from "./utils";

describe("YAML file size", () => {
  it("preserves YAML text including SQL literals", () => {
    const yaml = "query: |\n  select '{{value}}'\n";
    expect(checkFileSize(yaml)).toBe(yaml);
  });
  it("limits UTF-8 bytes rather than characters", () => {
    expect(() =>
      checkFileSize("中".repeat(Math.ceil(MAX_BUNDLE_BYTES / 3))),
    ).toThrow();
    expect(checkFileSize("a".repeat(MAX_BUNDLE_BYTES))).toHaveLength(
      MAX_BUNDLE_BYTES,
    );
  });
});
