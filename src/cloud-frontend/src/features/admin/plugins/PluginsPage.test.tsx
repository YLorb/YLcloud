import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { useSession } from "../../../app/session";
import { PluginsPage } from "./PluginsPage";
import { pluginApi, type PluginView } from "./pluginApi";
vi.mock("../../../app/session", () => ({ useSession: vi.fn() }));
vi.mock("./pluginApi", async importOriginal => ({ ...(await importOriginal<typeof import("./pluginApi")>()),
  pluginApi: { list: vi.fn(), install: vi.fn(), enable: vi.fn(), disable: vi.fn(), uninstall: vi.fn(), check: vi.fn() } }));
const initial: PluginView = { id: "example", name: "测试解析插件", version: "1", providerIds: ["example.parser"],
  declaredModes: ["LOCAL", "DOCKER"], configuredModes: [], managementState: "DISABLED", selectedMode: null, revision: 0,
  runtimeState: null, health: null, installationKind: "MANIFEST_REGISTRATION", persistence: "PROCESS_MEMORY" };
const running: PluginView = { ...initial, configuredModes: ["LOCAL"], managementState: "ENABLED", selectedMode: "LOCAL", runtimeState: "RUNNING",
  health: { runtimeId: "runtime-1", status: "HEALTHY", consecutiveFailures: 0, checkedAt: null } };
const clients: QueryClient[] = [];
function mount() { const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } }); clients.push(client);
  return render(<QueryClientProvider client={client}><PluginsPage /></QueryClientProvider>); }
beforeEach(() => {
  vi.mocked(useSession).mockReturnValue({ user: { id: 7, username: "owner", nickname: "Owner", deploymentOwner: true }, signIn: vi.fn(), signOut: vi.fn() });
  vi.mocked(pluginApi.list).mockResolvedValue([initial]);
});
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.clear()); vi.resetAllMocks(); });

it("非部署所有者不读取或操作插件", () => {
  vi.mocked(useSession).mockReturnValue({ user: { id: 8, username: "admin", nickname: "Admin", role: "ADMIN", deploymentOwner: false }, signIn: vi.fn(), signOut: vi.fn() });
  mount(); expect(screen.getByRole("alert")).toHaveTextContent("仅部署所有者"); expect(pluginApi.list).not.toHaveBeenCalled();
});
it("展示真实状态和内存登记限制，未配置后端不能启用", async () => {
  mount(); await screen.findByText("测试解析插件");
  expect(screen.getByText(/重启后会丢失/)).toBeInTheDocument();
  expect(screen.queryByText(/操作会持久保存/)).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "启用" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "卸载登记" })).toBeEnabled();
  expect(screen.getByText("未启动")).toBeInTheDocument();
});
it("空列表、加载失败和重试有明确提示", async () => {
  vi.mocked(pluginApi.list).mockRejectedValueOnce(new Error("服务不可用")).mockResolvedValue([]);
  const user = userEvent.setup(); mount(); await screen.findByText(/服务不可用/);
  await user.click(screen.getByRole("button", { name: "刷新状态" }));
  expect(await screen.findByText("尚未登记插件")).toBeInTheDocument();
});
it("保留重复字段原文并只在确认后登记", async () => {
  vi.mocked(pluginApi.install).mockResolvedValue(initial);
  const user = userEvent.setup(); mount(); await screen.findByText("测试解析插件");
  await user.click(screen.getByRole("button", { name: "登记说明书" }));
  const raw = '{"schemaVersion":1,"schemaVersion":1}';
  fireEvent.change(screen.getByLabelText("Manifest JSON"), { target: { value: raw } });
  expect(pluginApi.install).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "确认登记" }));
  await waitFor(() => expect(pluginApi.install).toHaveBeenCalledWith(raw));
  expect(await screen.findByText("说明书已登记，尚未启动解析引擎。")).toBeInTheDocument();
});
it("超限说明书不可提交且服务端错误保留输入", async () => {
  vi.mocked(pluginApi.install).mockRejectedValue(new Error("重复字段"));
  const user = userEvent.setup(); mount(); await screen.findByText("测试解析插件");
  await user.click(screen.getByRole("button", { name: "登记说明书" }));
  fireEvent.change(screen.getByLabelText("Manifest JSON"), { target: { value: "文".repeat(22000) } });
  expect(screen.getByRole("button", { name: "确认登记" })).toBeDisabled();
  fireEvent.change(screen.getByLabelText("Manifest JSON"), { target: { value: "{}" } });
  await user.click(screen.getByRole("button", { name: "确认登记" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("重复字段");
  expect(screen.getByLabelText("Manifest JSON")).toHaveValue("{}");
});
it("启用只允许选择服务器已配置模式，确认前不调用", async () => {
  vi.mocked(pluginApi.list).mockResolvedValue([{ ...initial, configuredModes: ["LOCAL"] }]);
  vi.mocked(pluginApi.enable).mockResolvedValue(running);
  const user = userEvent.setup(); mount(); await screen.findByText("测试解析插件");
  await user.click(screen.getByRole("button", { name: "启用" }));
  expect(screen.getByRole("button", { name: "确认启用" })).toBeDisabled();
  expect(screen.queryByRole("option", { name: "Docker" })).not.toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText("运行方式"), "LOCAL");
  expect(pluginApi.enable).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "确认启用" }));
  await waitFor(() => expect(pluginApi.enable).toHaveBeenCalledWith("example", "LOCAL"));
});
it("202 停止中不能误报成功，继续清理后才允许卸载", async () => {
  const stopping: PluginView = { ...running, managementState: "DISABLED", runtimeState: "STOPPING", health: { ...running.health!, status: "INACTIVE" } };
  vi.mocked(pluginApi.list).mockResolvedValue([running]);
  vi.mocked(pluginApi.disable).mockImplementation(async () => { vi.mocked(pluginApi.list).mockResolvedValue([stopping]); return { plugin: stopping, stopped: false }; });
  const user = userEvent.setup(); mount(); await screen.findByText("测试解析插件");
  await user.click(screen.getByRole("button", { name: "禁用并清理" }));
  await user.click(screen.getByRole("button", { name: "确认禁用并清理" }));
  await screen.findByText("停止中");
  expect(screen.getByRole("button", { name: "卸载登记" })).toBeDisabled();
  expect(screen.queryByText("已禁用，资源已释放。")).not.toBeInTheDocument();
  const stopped: PluginView = { ...stopping, runtimeState: "STOPPED" };
  vi.mocked(pluginApi.disable).mockImplementation(async () => { vi.mocked(pluginApi.list).mockResolvedValue([stopped]); return { plugin: stopped, stopped: true }; });
  await user.click(screen.getByRole("button", { name: "继续清理" }));
  await user.click(screen.getByRole("button", { name: "确认禁用并清理" }));
  await screen.findByText("已停止");
  await waitFor(() => expect(screen.getByRole("button", { name: "卸载登记" })).toBeEnabled());
});
it("隔离不能直接健康恢复，允许禁用；未停止不可卸载", async () => {
  vi.mocked(pluginApi.list).mockResolvedValue([{ ...running, health: { ...running.health!, status: "ISOLATED" } }]);
  mount(); await screen.findByText("已隔离");
  expect(screen.getByRole("button", { name: "检查健康" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "禁用并清理" })).toBeEnabled();
  expect(screen.getByRole("button", { name: "卸载登记" })).toBeDisabled();
});
it("冲突失败后刷新实际状态，卸载需要确认", async () => {
  vi.mocked(pluginApi.uninstall).mockRejectedValue(new Error("插件状态已改变"));
  const user = userEvent.setup(); mount(); await screen.findByText("测试解析插件");
  await user.click(screen.getByRole("button", { name: "卸载登记" }));
  expect(pluginApi.uninstall).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "确认卸载登记" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("插件状态已改变");
  await waitFor(() => expect(pluginApi.list).toHaveBeenCalledTimes(2));
});
