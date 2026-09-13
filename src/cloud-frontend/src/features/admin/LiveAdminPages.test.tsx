import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import { api } from "../../api";
import { AdminDataSourceProvider } from "./core/AdminDataSource";
import { LiveAdminDataSource } from "./core/LiveAdminDataSource";
import { UserGroupsPage } from "./pages/UserGroupsPage";
import { SettingsPage } from "./pages/SettingsPage";
import { AdminTasksPage } from "./pages/AdminTasksPage";
import { DashboardPage } from "./pages/DashboardPage";
import { FileSystemPage } from "./pages/FileSystemPage";

vi.mock("../../api", () => ({ api: Object.fromEntries([
  "permissionGroups", "permissionDefinitions", "createPermissionGroup", "updatePermissionGroup", "deletePermissionGroup", "groupQuota", "updateGroupQuota",
  "adminSettings", "updateAdminSettings", "maintenanceStatus", "enableMaintenance", "disableMaintenance", "adminTasks", "archiveAdminTask", "retryUnifiedTask", "cancelUnifiedTask", "adminDailyMetrics", "adminFullTextSearch"
].map((key) => [key, vi.fn()])) }));
const clients: QueryClient[] = [];
function mount(page: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } }); clients.push(client);
  return render(<QueryClientProvider client={client}><AdminDataSourceProvider dataSource={new LiveAdminDataSource()}>{page}</AdminDataSourceProvider></QueryClientProvider>);
}
beforeEach(() => {
  vi.mocked(api.permissionGroups).mockResolvedValue([{ id: 2, name: "课程组", systemGroup: false, userCount: 1, permissions: { upload: true } }]);
  vi.mocked(api.permissionDefinitions).mockResolvedValue([{ key: "upload", label: "上传文件", description: "上传权限" }]);
  vi.mocked(api.maintenanceStatus).mockResolvedValue({ active: false });
});
afterEach(() => { cleanup(); clients.splice(0).forEach((client) => client.clear()); vi.resetAllMocks(); });

describe("管理页真实接口", () => {
  it("首页区分引用和实体，并且不伪造 Token 统计", async () => {
    vi.mocked(api.adminDailyMetrics).mockResolvedValue({ timezone: "Asia/Shanghai", tokenStatisticsAvailable: false, days: [{ date: "2026-09-13", newFiles: 5, newBlobs: 2, newUsers: 1, newSpaces: 0 }] });
    mount(<DashboardPage />);
    expect(await screen.findByText("2026-09-13")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "新增 Blob" })).toBeInTheDocument();
    expect(screen.getByText("统计尚未接入")).toBeInTheDocument();
    expect(api.adminDailyMetrics).toHaveBeenCalledWith(7);
  });
  it("搜索需要提交，证据按纯文本展示", async () => {
    vi.mocked(api.adminFullTextSearch).mockResolvedValue({ total: 1, tookMs: 3, files: [{ documentId: 1, spaceId: 2, spaceFileId: 3, fileUuid: "uuid", fileName: "课程.txt", fileType: "txt", chunkCount: 1, matchType: "WORD_MATCH", evidences: [{ chunkId: 4, content: "<script>alert(1)</script>" }] }] });
    const user = userEvent.setup(); mount(<FileSystemPage />);
    expect(api.adminFullTextSearch).not.toHaveBeenCalled();
    await user.type(screen.getByLabelText("搜索内容"), "课程");
    await user.click(screen.getByRole("button", { name: "搜索" }));
    expect(await screen.findByText("课程.txt")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "查看证据" }));
    expect(screen.getByText("<script>alert(1)</script>")).toBeInTheDocument();
  });
  it("含成员的组不可删除，编辑权限保留原值", async () => {
    const user = userEvent.setup(); mount(<UserGroupsPage />);
    await screen.findByText("课程组");
    expect(screen.getByRole("button", { name: "删除" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "编辑权限" }));
    expect(screen.getByLabelText(/上传文件/)).toHaveValue("true");
  });
  it("读取并保存全部配额字段，不从默认零值覆盖已有策略", async () => {
    const quota = { groupId: 2, storageBytes: 1000, maxFileBytes: 500, spaceLimit: 3, monthlyApiCalls: 100, monthlyModelTokens: 200, monthlyAgentTasks: 5, concurrentAgentTasks: 2 };
    vi.mocked(api.groupQuota).mockResolvedValue(quota);
    vi.mocked(api.updateGroupQuota).mockResolvedValue(quota);
    const user = userEvent.setup(); mount(<UserGroupsPage />); await screen.findByText("课程组");
    await user.click(screen.getByRole("button", { name: "配额" }));
    expect(await screen.findByLabelText("存储容量（字节）")).toHaveValue(1000);
    await user.click(screen.getByRole("button", { name: "确认保存配额" }));
    const { groupId: _groupId, ...payload } = quota;
    await waitFor(() => expect(api.updateGroupQuota).toHaveBeenCalledWith(2, payload));
  });
  it("敏感配置不回填，保存普通配置不带未修改的密钥", async () => {
    vi.mocked(api.adminSettings).mockResolvedValue([
      { key: "site.name", label: "站点名", value: "Cloud", valueType: "string", groupName: "site", secret: false, editable: true },
      { key: "llm.apiKey", label: "密钥", value: "must-not-echo", valueType: "string", groupName: "ai", secret: true, editable: true }
    ]);
    vi.mocked(api.updateAdminSettings).mockResolvedValue(undefined);
    const user = userEvent.setup(); mount(<SettingsPage />);
    expect(await screen.findByLabelText(/密钥/)).toHaveValue("");
    const name = screen.getByLabelText(/站点名/); await user.clear(name); await user.type(name, "New Cloud");
    await user.click(screen.getByRole("button", { name: "保存配置" }));
    await user.click(screen.getByRole("button", { name: "确认保存到服务器" }));
    await waitFor(() => expect(api.updateAdminSettings).toHaveBeenCalledWith([{ key: "site.name", value: "New Cloud" }]));
  });
  it("维护操作必须确认，传递输入原因", async () => {
    vi.mocked(api.adminSettings).mockResolvedValue([]);
    vi.mocked(api.enableMaintenance).mockResolvedValue({ status: "enabled", message: "ok" });
    const user = userEvent.setup(); mount(<SettingsPage />);
    await user.click(screen.getByRole("tab", { name: "服务器与维护" }));
    await user.type(await screen.findByLabelText("维护原因"), "升级检查");
    await user.click(screen.getByRole("button", { name: "进入维护" }));
    expect(api.enableMaintenance).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "确认执行" }));
    await waitFor(() => expect(api.enableMaintenance).toHaveBeenCalledWith({ reason: "升级检查" }));
  });
  it("全站任务由服务端分页，归档必须确认", async () => {
    vi.mocked(api.adminTasks).mockResolvedValue({ items: [{ id: 9, task_type: "TEST", task_domain: "maintenance", status: "SUCCESS", attempt_version: 1, created_at: "2026-09-13", updated_at: "2026-09-13" }], total: 50, page: 1, pageSize: 20 });
    vi.mocked(api.archiveAdminTask).mockResolvedValue(true);
    const user = userEvent.setup(); mount(<AdminTasksPage />); await screen.findByText("TEST");
    await user.click(screen.getByRole("button", { name: "下一页" }));
    await waitFor(() => expect(api.adminTasks).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2, pageSize: 20 })));
    await user.click(await screen.findByRole("button", { name: "归档" }));
    expect(api.archiveAdminTask).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "确认执行" }));
    await waitFor(() => expect(api.archiveAdminTask).toHaveBeenCalledWith(9));
  });
});
