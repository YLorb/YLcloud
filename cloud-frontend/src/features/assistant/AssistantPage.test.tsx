import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { StrictMode } from "react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AssistantPage } from "./AssistantPage";

vi.mock("../../api", () => ({
  api: {
    listSpaces: vi.fn().mockResolvedValue([{ id: 48, name: "验收知识库" }]),
    listKnowledgeChatSessions: vi.fn().mockResolvedValue([{
      id: 7,
      userId: 42,
      title: "安装说明",
      spaceIds: [48],
      messageCount: 1
    }]),
    getKnowledgeChatSession: vi.fn().mockResolvedValue({
      id: 7,
      userId: 42,
      title: "安装说明",
      spaceIds: [48],
      messages: [{ id: 11, sessionId: 7, role: "user", content: "如何安装？", taskStatus: "SUCCESS" }]
    }),
    createKnowledgeChatSession: vi.fn(),
    updateKnowledgeChatSessionScope: vi.fn(),
    submitKnowledgeChatQuery: vi.fn(),
    deleteKnowledgeChatSession: vi.fn(),
    retryKnowledgeChatQuery: vi.fn(),
    listKnowledgeChatEpisodes: vi.fn().mockResolvedValue([]),
    submitKnowledgeChatFeedback: vi.fn()
  }
}));

describe("AssistantPage effects", () => {
  afterEach(() => cleanup());
  beforeEach(() => {
    Object.defineProperty(HTMLElement.prototype,"scrollIntoView",{
      configurable: true,
      value: vi.fn(() => "browser-specific-return-value")
    });
  });

  it("does not expose scrollIntoView's return value as a React effect cleanup", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <StrictMode>
        <QueryClientProvider client={client}>
          <MemoryRouter><AssistantPage /></MemoryRouter>
        </QueryClientProvider>
      </StrictMode>
    );

    expect(await screen.findByText("如何安装？")).toBeInTheDocument();
    expect(HTMLElement.prototype.scrollIntoView).toHaveBeenCalled();
  });

  it("keeps an explicit new-session draft instead of reselecting the first session", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter><AssistantPage /></MemoryRouter></QueryClientProvider>);

    expect(await screen.findByText("如何安装？")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button",{ name: "新建会话" }));

    expect(await screen.findByRole("heading",{ name: "开始一个新会话" })).toBeInTheDocument();
    expect(screen.queryByText("如何安装？")).not.toBeInTheDocument();
    await client.invalidateQueries({ queryKey: ["chat-sessions"] });
    expect(await screen.findByRole("heading",{ name: "开始一个新会话" })).toBeInTheDocument();
  });
});
