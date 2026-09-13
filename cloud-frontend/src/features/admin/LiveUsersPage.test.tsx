import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "../../api";
import type { AdminUser } from "../../types";
import { AdminDataSourceProvider } from "./core/AdminDataSource";
import { LiveAdminDataSource } from "./core/LiveAdminDataSource";
import { UsersPage } from "./pages/UsersPage";

vi.mock("../../api", () => ({ api: {
  adminUsers: vi.fn(), permissionDefinitions: vi.fn(), permissionGroups: vi.fn(),
  createAdminUser: vi.fn(), updateAdminUser: vi.fn(), updateAdminUserAccess: vi.fn()
} }));
const account: AdminUser = { id: 7, username: "alice", nickname: "Alice", role: "USER", deploymentOwner: false, status: 1,
  groupId: 2, groupName: "课程组", permissionOverrides: {}, effectivePermissions: { "file.upload": true } };
const mount = () => render(<AdminDataSourceProvider dataSource={new LiveAdminDataSource()}><UsersPage /></AdminDataSourceProvider>);

beforeEach(() => {
  vi.mocked(api.adminUsers).mockResolvedValue([account]);
  vi.mocked(api.permissionGroups).mockResolvedValue([{ id: 2, name: "课程组", systemGroup: false, userCount: 1, permissions: {} }]);
  vi.mocked(api.permissionDefinitions).mockResolvedValue([{ key: "file.upload", label: "上传文件", description: "上传权限" }]);
});
afterEach(() => { cleanup(); vi.resetAllMocks(); });

describe("真实用户管理", () => {
  it("读取服务器用户，不显示模拟容量或账号删除", async () => {
    mount();
    expect(await screen.findByText("Alice")).toBeInTheDocument();
    expect(screen.getByText("真实 API")).toBeInTheDocument();
    expect(screen.queryByText("0 B")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "移除" })).not.toBeInTheDocument();
  });
  it("创建提交密码并使用服务端返回值，关闭后不保留密码", async () => {
    vi.mocked(api.createAdminUser).mockResolvedValue({ ...account, id: 8, username: "bob", nickname: "Bob" });
    const user = userEvent.setup(); mount();
    await screen.findByText("Alice");
    await user.click(screen.getByRole("button", { name: "新建用户" }));
    const dialog = screen.getByRole("dialog");
    await user.type(within(dialog).getByLabelText("用户名"), "bob");
    await user.type(within(dialog).getByLabelText("昵称"), "Bob");
    await user.type(within(dialog).getByLabelText("初始密码"), "test-only-password");
    await user.click(within(dialog).getByRole("button", { name: "确认保存到服务器" }));
    expect(await screen.findByText("Bob")).toBeInTheDocument();
    expect(api.createAdminUser).toHaveBeenCalledWith(expect.objectContaining({ username: "bob", password: "test-only-password", role: "USER" }));
    await user.click(screen.getByRole("button", { name: "新建用户" }));
    expect(screen.getByLabelText("初始密码")).toHaveValue("");
  });
  it("拒绝修改时保留表单并显示后端错误，不乐观修改列表", async () => {
    vi.mocked(api.updateAdminUser).mockRejectedValue(new Error("不能修改自己的角色或状态"));
    const user = userEvent.setup(); mount(); await screen.findByText("Alice");
    await user.click(screen.getByRole("button", { name: "角色与状态" }));
    await user.selectOptions(screen.getByLabelText("账号状态"), "0");
    await user.click(screen.getByRole("button", { name: "确认保存到服务器" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("不能修改自己的角色或状态");
    expect(api.updateAdminUser).toHaveBeenCalledWith(7, { role: "USER", status: 0 });
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });
  it("解除分组并提交权限覆盖，继承项不发送为 false", async () => {
    vi.mocked(api.updateAdminUserAccess).mockResolvedValue({ ...account, groupId: null, groupName: null, permissionOverrides: { "file.upload": false } });
    const user = userEvent.setup(); mount(); await screen.findByText("Alice");
    await user.click(screen.getByRole("button", { name: "分组与权限" }));
    await user.selectOptions(screen.getByLabelText("用户组"), "");
    await user.selectOptions(screen.getByLabelText(/上传文件/), "false");
    await user.click(screen.getByRole("button", { name: "确认保存到服务器" }));
    await waitFor(() => expect(api.updateAdminUserAccess).toHaveBeenCalledWith(7, { groupId: undefined, clearGroup: true, overrides: { "file.upload": false } }));
    expect(await screen.findByText("未分组")).toBeInTheDocument();
  });
  it("辅助接口失败不隐藏用户，但禁用权限编辑", async () => {
    vi.mocked(api.permissionDefinitions).mockRejectedValue(new Error("offline")); mount();
    expect(await screen.findByText("Alice")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "分组与权限" })).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("权限定义或用户组加载失败");
  });
  it("列表失败可重试，不将错误当作空数据", async () => {
    vi.mocked(api.adminUsers).mockRejectedValueOnce(new Error("network error"));
    const user = userEvent.setup(); mount();
    expect(await screen.findByRole("alert")).toHaveTextContent("network error");
    await user.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText("Alice")).toBeInTheDocument();
  });
  it("未适配的数据源拒绝所有写操作", async () => {
    const source = new LiveAdminDataSource();
    await expect(source.create("storage", {})).rejects.toThrow("尚未接入后端");
    await expect(source.update("storage", 1, {})).rejects.toThrow("尚未接入后端");
    await expect(source.remove("storage", 1)).rejects.toThrow("尚未接入后端");
  });
});
