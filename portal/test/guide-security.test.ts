import { runInNewContext } from "node:vm";
import { describe, expect, it } from "vitest";
import { ADMIN_PAGE } from "../src/admin-page";
import { normalizeGuideStatus } from "../src/index";

// Run the actual shipped renderer directly against old DB telemetry, without server normalization.
const esc = ADMIN_PAGE.match(/^const esc = .*;$/m)![0];
const guideLine = ADMIN_PAGE.match(/^function guideLine\(g\) \{[\s\S]*?^\}/m)![0];
const render = (g: unknown): string => runInNewContext(esc + "\n" + guideLine + "\nguideLine(input)", { input: g });
const hostile = '<img src="data:,x" onerror="alert(1)">';
describe("guide telemetry trust boundary", () => {
  it("renders every count sibling as a number even for preexisting hostile stored data", () => {
    for (const field of ["switched", "thin", "unmatchedCount", "linked"]) {
      for (const value of [hostile, [hostile], { x: hostile }]) {
        const g = { switched: 1, thin: 2, unmatchedCount: 3, linked: { direct: 4 }, unmatched: [], [field]: value };
        const html = render(g);
        expect(html).not.toContain("<img");
        expect(html).not.toContain("onerror");
      }
    }
  });
  it("escapes ordinary names/errors and tolerates malformed arrays", () => {
    expect(render({ switched: 1, thin: 2, unmatchedCount: 3, unmatched: [hostile], errors: [hostile] })).toContain("&lt;img");
    expect(render({ unmatchedCount: 3, unmatched: {}, errors: {} })).not.toContain("<img");
    expect(render({ switched: 2, thin: 3, linked: { a: 4, b: 5 } })).toContain("9 channels linked");
  });
  it("normalizes new count inputs separately while retaining safely rendered text", () => {
    expect(normalizeGuideStatus({ playing: { title: hostile }, guide: { switched: hostile, thin: [], unmatchedCount: {}, linked: { a: hostile, b: 2 } } })).toEqual({
      playing: { title: hostile }, guide: { switched: 0, thin: 0, unmatchedCount: 0, linked: { a: 0, b: 2 } },
    });
    expect(normalizeGuideStatus({ guide: [hostile] })).toEqual({ guide: null });
    expect(normalizeGuideStatus(null)).toBeNull();
  });
});
