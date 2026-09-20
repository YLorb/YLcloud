import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AuthorizedPreview } from "./AuthorizedPreview";

vi.mock("../../api", () => ({ handleAuthenticationStatus: vi.fn() }));
const fetchMock = vi.fn();
const createUrl = vi.fn(() => "blob:test-pdf");
const revokeUrl = vi.fn();
const pdf = () => new Response(new Blob(["%PDF-1.4"]), { headers: { "Content-Type": "application/pdf" } });

describe("authorized Office preview", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: createUrl });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: revokeUrl });
  });
  afterEach(() => { cleanup(); vi.clearAllMocks(); vi.unstubAllGlobals(); });

  it("authenticates the PDF request and releases the URL on close", async () => {
    fetchMock.mockResolvedValueOnce(pdf());
    const view = render(<AuthorizedPreview url="/api/file/preview/id/stream" title="报告.docx" />);
    expect(await screen.findByTitle("报告.docx")).toHaveAttribute("src", "blob:test-pdf");
    expect(fetchMock).toHaveBeenCalledWith("/api/file/preview/id/stream", expect.objectContaining({ credentials: "same-origin" }));
    expect(screen.getByRole("link", { name: "在新窗口查看" })).toHaveAttribute("href", "blob:test-pdf");
    view.unmount();
    expect(revokeUrl).toHaveBeenCalledWith("blob:test-pdf");
  });

  it("shows the server error and retries the conversion", async () => {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ message: "预览服务繁忙，请稍后重试" }), { status: 400, headers: { "Content-Type": "application/json" } })).mockResolvedValueOnce(pdf());
    render(<AuthorizedPreview url="/api/space/1/files/2/preview/stream" title="预算.xlsx" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("预览服务繁忙");
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByTitle("预算.xlsx")).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("ignores a late response after the preview closes", async () => {
    let resolve!: (value: Response) => void;
    fetchMock.mockReturnValueOnce(new Promise<Response>((done) => { resolve = done; }));
    const view = render(<AuthorizedPreview url="/api/file/preview/slow/stream" title="演示.pptx" />);
    view.unmount();
    resolve(pdf());
    await waitFor(() => expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true));
    expect(createUrl).not.toHaveBeenCalled();
  });
});
