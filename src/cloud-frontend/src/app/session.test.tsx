import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { api, ApiError } from "../api";
import { SessionProvider, useSession } from "./session";

vi.mock("../api", async (original) => ({ ...await original<typeof import("../api")>(), api: { session: vi.fn(), logout: vi.fn() } }));
vi.mock("sonner", () => ({ toast: { error: vi.fn() } }));
function Consumer() {
  const { user, signOut } = useSession();
  return <><span>{user?.username ?? "anonymous"}</span><button onClick={() => void signOut().catch(() => {})}>logout</button></>;
}
function mount() {
  const client = new QueryClient();
  render(<QueryClientProvider client={client}><SessionProvider><Consumer /></SessionProvider></QueryClientProvider>);
}
beforeEach(() => { vi.resetAllMocks(); localStorage.clear(); });
afterEach(cleanup);
it("restores identity from server and removes legacy browser credentials", async () => {
  localStorage.setItem("ylcloud_token", "old-token");
  vi.mocked(api.session).mockResolvedValue({ id: 1, username: "alice", nickname: "Alice" });
  mount();
  expect(await screen.findByText("alice")).toBeInTheDocument();
  expect(localStorage.getItem("ylcloud_token")).toBeNull();
});
it("treats expired sessions as anonymous", async () => {
  vi.mocked(api.session).mockRejectedValue(new ApiError("expired", 401));
  mount();
  expect(await screen.findByText("anonymous")).toBeInTheDocument();
});
it("shows retry instead of login on a service outage", async () => {
  vi.mocked(api.session).mockRejectedValueOnce(new ApiError("unavailable", 503))
    .mockResolvedValueOnce({ id: 1, username: "alice", nickname: "Alice" });
  mount();
  expect(await screen.findByRole("alert")).toHaveTextContent("unavailable");
  expect(screen.queryByText("anonymous")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "重试" }));
  expect(await screen.findByText("alice")).toBeInTheDocument();
});
it("retains identity if logout fails and clears it on successful retry", async () => {
  vi.mocked(api.session).mockResolvedValue({ id: 1, username: "alice", nickname: "Alice" });
  vi.mocked(api.logout).mockRejectedValueOnce(new ApiError("unavailable", 503)).mockResolvedValueOnce(true);
  mount();
  await screen.findByText("alice");
  fireEvent.click(screen.getByRole("button", { name: "logout" }));
  await vi.waitFor(() => expect(api.logout).toHaveBeenCalledTimes(1));
  expect(screen.getByText("alice")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "logout" }));
  expect(await screen.findByText("anonymous")).toBeInTheDocument();
});
