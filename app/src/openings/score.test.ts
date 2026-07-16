import { describe, it, expect } from "vitest";
import { scoreToCp, formatScore, cpToBar } from "./score";

describe("scoreToCp", () => {
  it("passes through centipawns", () => {
    expect(scoreToCp({ cp: 35, mate: null })).toBe(35);
    expect(scoreToCp({ cp: -120, mate: null })).toBe(-120);
  });
  it("maps mate to large magnitudes with the right sign", () => {
    expect(scoreToCp({ cp: null, mate: 3 })).toBeGreaterThan(50_000);
    expect(scoreToCp({ cp: null, mate: -2 })).toBeLessThan(-50_000);
    // faster mate scores higher than slower mate
    expect(scoreToCp({ cp: null, mate: 1 })).toBeGreaterThan(scoreToCp({ cp: null, mate: 5 }));
  });
});

describe("formatScore", () => {
  it("formats pawns and mates", () => {
    expect(formatScore({ cp: 35, mate: null })).toBe("+0.35");
    expect(formatScore({ cp: -140, mate: null })).toBe("-1.40");
    expect(formatScore({ cp: null, mate: 4 })).toBe("#4");
    expect(formatScore({ cp: null, mate: -3 })).toBe("#-3");
  });
});

describe("cpToBar", () => {
  it("is 0.5 at equality and monotonic", () => {
    expect(cpToBar(0)).toBeCloseTo(0.5, 5);
    expect(cpToBar(400)).toBeGreaterThan(0.7);
    expect(cpToBar(-400)).toBeLessThan(0.3);
    expect(cpToBar(100)).toBeGreaterThan(cpToBar(-100));
  });
});
