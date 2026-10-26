import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "../../api";
import { uploadFileWithResume } from "./multipartUpload";

vi.mock("../../api", async (original) => {
  const actual = await original<typeof import("../../api")>();
  return { ...actual, api: { initMultipartUpload: vi.fn(), multipartStatus: vi.fn(), uploadChunk: vi.fn(), mergeMultipartUpload: vi.fn() } };
});
vi.mock("hash-wasm", () => ({ md5: async () => "synthetic-hash" }));
beforeEach(() => {
  vi.useFakeTimers(); vi.clearAllMocks(); localStorage.clear();
  vi.stubGlobal("Worker", class {
    onmessage: ((event: { data: object }) => void) | null = null;
    postMessage() { setTimeout(() => this.onmessage?.({ data: { type: "result", md5: "m", sha1: "s", sha256: "h" } }), 0); }
    terminate() {}
  });
});
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

describe("multipart Gateway rate-limit recovery", () => {
  it("waits for init, status, chunk and final merge 429s, then completes without losing progress", async () => {
    const limited = () => new ApiError("limit", 429, 1);
    vi.mocked(api.initMultipartUpload).mockRejectedValueOnce(limited()).mockResolvedValue({ uploadId: "fixture", instantUpload: false, totalChunks: 1, chunkSize: 5 });
    vi.mocked(api.multipartStatus).mockRejectedValueOnce(limited()).mockResolvedValue({ uploadId: "fixture", totalChunks: 1, uploadedCount: 0, uploadedChunks: [] });
    vi.mocked(api.uploadChunk).mockRejectedValueOnce(limited()).mockResolvedValue(true);
    const file = { fileUuid: "finished" } as Awaited<ReturnType<typeof api.mergeMultipartUpload>>;
    vi.mocked(api.mergeMultipartUpload).mockRejectedValueOnce(limited()).mockResolvedValue(file);
    const input = new File(["12345"], "fixture.bin");
    vi.spyOn(input, "slice").mockReturnValue({ arrayBuffer: async () => new ArrayBuffer(5) } as Blob);
    const result = uploadFileWithResume(input, 0, 1);
    const assertion = expect(result).resolves.toBe(file);
    await vi.runAllTimersAsync(); await assertion;
    expect(api.initMultipartUpload).toHaveBeenCalledTimes(2);
    expect(api.multipartStatus).toHaveBeenCalledTimes(2);
    expect(api.uploadChunk).toHaveBeenCalledTimes(2);
    expect(api.mergeMultipartUpload).toHaveBeenCalledTimes(2);
    expect(localStorage.length).toBe(0);
  });
  it("fails once on a 503 instead of retrying a database or Redis outage", async () => {
    vi.mocked(api.initMultipartUpload).mockReset().mockRejectedValue(new ApiError("unavailable", 503));
    const result = uploadFileWithResume(new File(["12345"], "fixture.bin"), 0, 1);
    const assertion = expect(result).rejects.toMatchObject({ status: 503 });
    await vi.runAllTimersAsync(); await assertion;
    expect(api.initMultipartUpload).toHaveBeenCalledTimes(1);
  });
});
