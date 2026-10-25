import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "../../api";
import { KnowledgeSettings } from "./KnowledgeSettings";

vi.mock("../../api", () => ({ api: { ragConfig: vi.fn(), updateRagConfig: vi.fn(), retentionImpact: vi.fn() } }));
vi.mock("sonner", () => ({ toast: { success: vi.fn() } }));
const base = { chunkSize: 500, chunkOverlap: 50, topK: 10, scoreThreshold: 0.3, configRevision: 4,
  allowStaleDuringReindex: false, historicalEvidenceEnabled: false, historyRetentionDays: null };
function mount(canManage = true) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><KnowledgeSettings spaceId={1} canManage={canManage} onSaved={vi.fn()} /></QueryClientProvider>);
}
describe("KnowledgeSettings trust policies", () => {
  afterEach(cleanup);
  beforeEach(() => {
    vi.clearAllMocks(); vi.mocked(api.ragConfig).mockResolvedValue(base);
    vi.mocked(api.retentionImpact).mockResolvedValue({ affectedRevisions: 2, currentlyAccessible: 5, configRevision: 4 });
    vi.mocked(api.updateRagConfig).mockImplementation(async (_id, payload) => ({ ...payload, configRevision: 5 }));
  });
  it("keeps member configuration read-only", async () => {
    mount(false);
    const toggle = await screen.findByRole("switch", { name: "允许历史版本复核" });
    expect(toggle).toBeDisabled();
    expect(screen.queryByRole("button", { name: "保存配置" })).not.toBeInTheDocument();
    expect(api.updateRagConfig).not.toHaveBeenCalled();
  });
  it("requires retention when enabling history and sends config revision", async () => {
    mount();
    fireEvent.click(await screen.findByRole("switch", { name: "允许历史版本复核" }));
    expect(screen.getByRole("button", { name: "保存配置" })).toBeDisabled();
    fireEvent.change(screen.getByRole("spinbutton", { name: "历史版本保留天数" }), { target: { value: "30" } });
    fireEvent.click(screen.getByRole("button", { name: "保存配置" }));
    await waitFor(() => expect(api.updateRagConfig).toHaveBeenCalledWith(1, expect.objectContaining({
      historicalEvidenceEnabled: true, allowStaleDuringReindex: false, historyRetentionDays: 30, configRevision: 4
    })));
  });
  it("shows a server concurrency conflict without losing the user's draft", async () => {
    vi.mocked(api.updateRagConfig).mockRejectedValue(new Error("知识库配置已被其他管理员修改，请刷新后重试"));
    mount();
    fireEvent.click(await screen.findByRole("switch", { name: "更新期间允许使用旧版本" }));
    fireEvent.click(screen.getByRole("button", { name: "保存配置" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("其他管理员");
    expect(screen.getByRole("switch", { name: "更新期间允许使用旧版本" })).toBeChecked();
    expect(screen.getByRole("button", { name: "重新加载配置" })).toBeInTheDocument();
  });
  it("warns when an enabled retention period becomes shorter", async () => {
    vi.mocked(api.ragConfig).mockResolvedValue({ ...base, historicalEvidenceEnabled: true, historyRetentionDays: 90 });
    mount();
    fireEvent.change(await screen.findByRole("spinbutton", { name: "历史版本保留天数" }), { target: { value: "7" } });
    expect(screen.getByRole("status")).toHaveTextContent("保留期限将缩短");
    fireEvent.click(screen.getByRole("button", { name: "保存配置" }));
    expect(await screen.findByRole("dialog", { name: "确认历史版本访问变化" })).toHaveTextContent("2 个");
    expect(api.updateRagConfig).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "确认变更" }));
    await waitFor(() => expect(api.updateRagConfig).toHaveBeenCalledWith(1,expect.objectContaining({ historyRetentionDays: 7, configRevision: 4 })));
  });
  it("blocks a stale impact preview and leaves settings unchanged", async () => {
    vi.mocked(api.ragConfig).mockResolvedValue({ ...base, historicalEvidenceEnabled: true, historyRetentionDays: 90 });
    vi.mocked(api.retentionImpact).mockResolvedValue({ affectedRevisions: 2, currentlyAccessible: 5, configRevision: 5 });
    mount();
    fireEvent.click(await screen.findByRole("switch", { name: "允许历史版本复核" }));
    fireEvent.click(screen.getByRole("button", { name: "保存配置" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("重新加载后确认影响");
    expect(api.updateRagConfig).not.toHaveBeenCalled();
  });
});
