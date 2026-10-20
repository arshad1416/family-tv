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
    const html = render({ switched: 1, unmatchedCount: 3, unmatched: [{ toString: null }, "Good channel", ["ignored"], null], errors: [{ toString: 1 }, "Good error", 42] });
    expect(html).toContain("Good channel");
    expect(html).toContain("Good error");
    expect(html).not.toContain("ignored");
    expect(html).not.toContain("[object Object]");
    expect(render({ unmatchedCount: 1, unmatched: ["x".repeat(2000)], errors: Array(101).fill("bounded") }).length).toBeLessThan(3000);
  });
  it("normalizes new count inputs separately while retaining safely rendered text", () => {
    expect(normalizeGuideStatus({ playing: { title: hostile }, guide: { switched: hostile, thin: [], unmatchedCount: {}, linked: { a: hostile, b: 2 } } })).toEqual({
      playing: { title: hostile }, guide: { switched: 0, thin: 0, unmatchedCount: 0, linked: { a: 0, b: 2 } },
    });
    expect(normalizeGuideStatus({ guide: [hostile] })).toEqual({ guide: null });
    expect(normalizeGuideStatus(null)).toBeNull();
  });
});

const pick = (name: string) => ADMIN_PAGE.match(new RegExp("^function " + name + "\\(d\\) \\{[\\s\\S]*?^\\}", "m"))![0];
const run = (code: string, input: unknown, verdicts: unknown = {}) => runInNewContext(esc + "\n" + guideLine + "\n" + pick("appName") + "\n" + pick("currentCheck") + "\n" + code, { input, verdicts });

describe("device cards", () => {
  it("says the extra guide is unavailable when none loaded, in one plain line", () => {
    const html = render({ thin: 49605, switched: 0, linked: {}, unmatchedCount: 49605, unmatched: ["A"], errors: ["guide 1: HTTP 404", "guide 2: HTTP 404"] });
    expect(html).toContain("Extra guide unavailable");
    expect(html).not.toContain("49605");
    expect(html).not.toContain('class="err"');
    // Some loaded: the counts stay, with one quiet line for the rest.
    const some = render({ thin: 10, switched: 4, linked: { name: 4 }, unmatchedCount: 6, unmatched: [], errors: ["guide 2: HTTP 404"] });
    expect(some).toContain("4 of 10 thin channels");
    expect(some).toContain("Some extra guides did not load");
  });
  it("names the app, from the TV or else its version line", () => {
    expect(run("appName(input)", { status: { app: "rust" }, app_version: "0.2.9" })).toBe("Rust");
    expect(run("appName(input)", { status: null, app_version: "0.2.74" })).toBe("Kotlin");
    expect(run("appName(input)", { status: {}, app_version: "0.1.125" })).toBe("Rust");
    expect(run("appName(input)", { status: { platform: "ios" }, app_version: "0.1.0" })).toBe("Apple");
  });
  it("drops a check after 6 hours, or once the TV plays again after a problem", () => {
    const verdicts = { SERVER_ERROR: { label: "Provider server error", severity: 2 }, OK: { label: "Working", severity: 0 } };
    const hour = 3600 * 1000;
    const check = (d: object) => run("currentCheck(input)", d, verdicts);
    expect(check({ last_verdict: "SERVER_ERROR", last_verdict_at: Date.now() - hour })?.label).toBe("Provider server error");
    expect(check({ last_verdict: "SERVER_ERROR", last_verdict_at: Date.now() - 25 * hour })).toBeNull();
    expect(check({ last_verdict: "SERVER_ERROR", last_verdict_at: Date.now() - hour, status: { playing: { title: "News" } } })).toBeNull();
    expect(check({ last_verdict: "OK", last_verdict_at: Date.now() - hour, status: { playing: { title: "News" } } })?.label).toBe("Working");
  });
});
