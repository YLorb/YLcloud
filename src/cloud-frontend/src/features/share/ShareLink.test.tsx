import { StrictMode } from "react";
import { render, screen, waitFor, fireEvent, cleanup } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { ShareLinkDialog } from "./ShareLinkDialog";
import { PublicShareView } from "./PublicShareView";
import { shareApi } from "./shareApi";
vi.mock("./shareApi", async (original) => {
  const actual = await original<typeof import("./shareApi")>();
  return { ...actual, shareApi: { create: vi.fn(), update: vi.fn(), open: vi.fn(), verify: vi.fn() } };
});
const target = { sourceType: "PERSONAL" as const, sourceId: 101, name: "文件.txt" };
describe("share-link user flows", () => {
  afterEach(cleanup);
  beforeEach(() => vi.clearAllMocks());
  it("keeps password protection and forced download mutually exclusive", () => {
    render(<ShareLinkDialog target={target} onClose={() => {}} />);
    fireEvent.click(screen.getByLabelText("密码保护"));
    expect(screen.getByLabelText("打开链接直接下载")).toBeDisabled();
    fireEvent.click(screen.getByLabelText("密码保护"));
    fireEvent.click(screen.getByLabelText("打开链接直接下载"));
    expect(screen.getByLabelText("密码保护")).toBeDisabled();
  });
  it("creates an unlimited permanent link and shows the copyable result", async () => {
    vi.mocked(shareApi.create).mockResolvedValue({ ...target, id: 1, shortCode: "11aaaaaa", creatorId: 1, directory: false, expiresAt: null, maxDownloads: null, downloadCount: 0, passwordEnabled: false, forceDownload: false, state: "ACTIVE", version: 1 });
    render(<ShareLinkDialog target={target} onClose={() => {}} />);
    fireEvent.click(screen.getByText("生成链接"));
    expect(await screen.findByLabelText("短链接")).toHaveValue(window.location.origin + "/s/11aaaaaa");
    expect(shareApi.create).toHaveBeenCalledWith(target, expect.objectContaining({ expiryMode: "PERMANENT", maxDownloads: null, passwordEnabled: false, forceDownload: false }));
  });
  it("logs one opening even when React replays effects, and never exposes folder contents", async () => {
    vi.mocked(shareApi.open).mockResolvedValue({ state: "READY", name: "资料", directory: true, forceDownload: false, downloadToken: "credential" });
    render(<StrictMode><PublicShareView shareCode="11aaaaaa" settings={null} /></StrictMode>);
    expect(await screen.findByText("资料")).toBeInTheDocument();
    expect(shareApi.open).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "下载 ZIP" })).toBeInTheDocument();
    expect(document.querySelector("iframe")).toBeNull();
    expect(document.querySelector('input[name="credential"]')).toHaveValue("credential");
  });
  it("shows the password gate without a filename and verifies before download", async () => {
    vi.mocked(shareApi.open).mockResolvedValue({ state: "PASSWORD_REQUIRED", directory: false, forceDownload: false, visitToken: "visit" });
    vi.mocked(shareApi.verify).mockResolvedValue({ state: "READY", name: "秘密.txt", directory: false, forceDownload: false, downloadToken: "download" });
    render(<PublicShareView shareCode="11aaaaaa" settings={null} />);
    fireEvent.change(await screen.findByLabelText("提取密码"), { target: { value: "secret" } });
    expect(screen.queryByText("秘密.txt")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "确认" }));
    expect(await screen.findByText("秘密.txt")).toBeInTheDocument();
    expect(shareApi.verify).toHaveBeenCalledWith("11aaaaaa", "secret", "visit");
  });
  it("renders a uniform expired state with no download form", async () => {
    vi.mocked(shareApi.open).mockResolvedValue({ state: "EXPIRED", message: "文件已过期", directory: false, forceDownload: false });
    render(<PublicShareView shareCode="unknown" settings={null} />);
    await waitFor(() => expect(screen.getByRole("heading", { name: "文件已过期" })).toBeInTheDocument());
    expect(document.querySelector("form")).toBeNull();
  });
});
