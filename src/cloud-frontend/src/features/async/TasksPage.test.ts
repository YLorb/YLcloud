import { describe, expect, it } from "vitest";
import { getTaskRetryTarget, taskParentLabel } from "./TasksPage";

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

  it("labels RAG fan-out children with their parent task", () => {
    expect(taskParentLabel({ id: 4, source: "unified", parentTaskId: 3 })).toBe("父任务 #3 · ");
    expect(taskParentLabel({ id: 3, source: "unified" })).toBe("");
  });
});
