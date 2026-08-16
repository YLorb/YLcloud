import { describe, expect, it } from "vitest";
import { extOf, matchesCategory } from "./fileUtils";
import type { FileItem } from "./types";

function file(name: string, type?: string): FileItem {
  return { fileId: 1, fileUuid: "file-1", isDir: false, parentId: 8, name, type };
}

describe("file category matching", () => {
  it("normalizes stored extensions with a leading dot", () => {
    expect(extOf(file("report.pdf", ".pdf"))).toBe("pdf");
    expect(matchesCategory(file("report.pdf", ".pdf"), "documents")).toBe(true);
  });

  it("matches categories case-insensitively from filenames", () => {
    expect(matchesCategory(file("PHOTO.PNG"), "images")).toBe(true);
    expect(matchesCategory(file("clip.MP4"), "videos")).toBe(true);
  });
});
