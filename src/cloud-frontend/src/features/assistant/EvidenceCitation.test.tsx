import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "../../api";
import { EvidenceCitation } from "./EvidenceCitation";

vi.mock("../../api", () => ({ api: { ragEvidence: vi.fn() } }));
describe("EvidenceCitation", () => {
  afterEach(cleanup);
  beforeEach(() => vi.clearAllMocks());
  it("resolves authorized evidence and does not follow arbitrary source URLs", async () => {
    const citation = { spaceId: 1, evidenceId: "e-1", revisionId: 1, pageStart: 3, fileName: "退款政策.pdf", previewUrl: "https://example.invalid/file" };
    vi.mocked(api.ragEvidence).mockResolvedValue({ citation, content: "仅适用于未使用的商品。" });
    render(<EvidenceCitation citation={citation} index={0} />);
    fireEvent.click(screen.getByRole("button", { name: "核验原文" }));
    expect(await screen.findByText("仅适用于未使用的商品。")).toBeInTheDocument();
    expect(api.ragEvidence).toHaveBeenCalledWith(1,"e-1");
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.getAllByText(/第 3 页/).length).toBeGreaterThan(0);
  });
  it("shows revoked access without returning an original-file fallback", async () => {
    vi.mocked(api.ragEvidence).mockRejectedValue(new Error("该依据当前不可访问"));
    render(<EvidenceCitation citation={{ spaceId: 1, evidenceId: "e-1" }} index={0} />);
    fireEvent.click(screen.getByRole("button", { name: "核验原文" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("该依据当前不可访问");
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });
  it("marks legacy citations as unverified", () => {
    render(<EvidenceCitation citation={{ previewUrl: "/api/file/123", fileName: "旧记录" }} index={0} />);
    expect(screen.getByText("历史引用尚未绑定可核验版本")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "核验原文" })).not.toBeInTheDocument();
  });
  it("ignores a late response from a closed dialog after a newer access check fails", async () => {
    let complete!: (value: Awaited<ReturnType<typeof api.ragEvidence>>) => void;
    vi.mocked(api.ragEvidence).mockReturnValueOnce(new Promise((resolve) => { complete = resolve; }))
      .mockRejectedValueOnce(new Error("该依据当前不可访问"));
    render(<EvidenceCitation citation={{ spaceId: 1, evidenceId: "e-1" }} index={0} />);
    fireEvent.click(screen.getByRole("button", { name: "核验原文" }));
    fireEvent.click(await screen.findByRole("button", { name: "关闭对话框" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "核验原文" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("该依据当前不可访问");
    await act(async () => complete({ citation: { spaceId: 1, evidenceId: "e-1" }, content: "过期响应原文" }));
    expect(screen.queryByText("过期响应原文")).not.toBeInTheDocument();
  });
});
