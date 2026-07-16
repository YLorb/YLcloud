import { describe, expect, it } from "vitest";
import { getTaskRetryTarget } from "./TasksPage";

describe("background task retry routing", () => {
  it("keeps RAG tasks on the RAG retry endpoint", () => {
    expect(getTaskRetryTarget({ id: 1, source: "rag", status: "FAILED" })).toBe("rag");
  });

  it("keeps knowledge-profile tasks on the profile retry endpoint", () => {
    expect(getTaskRetryTarget({ id: 2, source: "knowledge", status: "FAILED" })).toBe("knowledge");
  });

  it("refuses to guess the retry endpoint for unknown sources", () => {
    expect(getTaskRetryTarget({ id: 3, status: "FAILED" })).toBeNull();
  });
});
