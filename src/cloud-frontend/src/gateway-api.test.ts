import { afterEach, describe, expect, it, vi } from "vitest";

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); vi.restoreAllMocks(); });
describe("Gateway integration", () => {
  it("honors Retry-After across routes and resumes without retrying rejected writes", async () => {
    vi.resetModules(); vi.useFakeTimers(); vi.setSystemTime(new Date("2026-10-26T00:00:00Z"));
    const { request } = await import("./api");
    const fetch = vi.fn().mockResolvedValueOnce(new Response("", { status: 429, headers: { "Retry-After": "20", "X-YLCloud-RateLimit": "gateway" } }))
      .mockResolvedValue(new Response(JSON.stringify({ code: 200, data: true }), { headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetch);
    await expect(request("/api/file/upload", { method: "POST" })).rejects.toMatchObject({ status: 429, retryAfterSeconds: 20 });
    await expect(request("/api/async/list")).rejects.toMatchObject({ status: 429 });
    expect(fetch).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(20_000);
    await expect(request("/api/async/list")).resolves.toBe(true);
    expect(fetch).toHaveBeenCalledTimes(2);
  });
  it("keeps login-specific rate limits local to the login operation", async () => {
    vi.resetModules();
    const { request } = await import("./api");
    const fetch = vi.fn().mockResolvedValueOnce(new Response("", { status: 429, headers: { "Retry-After": "900" } }))
      .mockResolvedValue(new Response(JSON.stringify({ code: 200, data: true }), { headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetch);
    await expect(request("/api/login", { method: "POST" })).rejects.toMatchObject({ status: 429, retryAfterSeconds: 900 });
    await expect(request("/api/site/public-settings")).resolves.toBe(true);
    expect(fetch).toHaveBeenCalledTimes(2);
  });
  it("navigates to authorized direct download instead of fetching cross-origin bytes", async () => {
    vi.resetModules(); const { api } = await import("./api");
    const url = "https://objects.example.test/bucket/file?X-Amz-Signature=fixture";
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ downloadUrl: url }), { headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetch);
    let visited = "";
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function(this: HTMLAnchorElement) { visited = this.href; });
    await api.downloadFile("file", 0, "file.txt");
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls[0][1].headers.get("X-YLCloud-Download")).toBe("1");
    expect(visited).toBe(url);
  });
});
