import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { AdminDataSourceProvider, MockAdminDataSource } from "./core/AdminDataSource";
import { DashboardPage } from "./pages/DashboardPage";
import { SettingsPage } from "./pages/SettingsPage";
import { FileSystemPage } from "./pages/FileSystemPage";
import { StoragePoliciesPage } from "./pages/StoragePoliciesPage";
import { NodesPage } from "./pages/NodesPage";
import { UserGroupsPage } from "./pages/UserGroupsPage";
import { UsersPage } from "./pages/UsersPage";
import { AdminFilesPage } from "./pages/AdminFilesPage";
import { SharesPage } from "./pages/SharesPage";
import { AdminTasksPage } from "./pages/AdminTasksPage";
import { OrdersPage } from "./pages/OrdersPage";
import { EventsPage } from "./pages/EventsPage";
import { ReportsPage } from "./pages/ReportsPage";
import { OAuthAppsPage } from "./pages/OAuthAppsPage";

afterEach(cleanup);

const pages = [
  ["面板首页", DashboardPage], ["参数设置", SettingsPage], ["文件系统", FileSystemPage],
  ["存储策略", StoragePoliciesPage], ["节点", NodesPage], ["用户组", UserGroupsPage],
  ["用户", UsersPage], ["文件", AdminFilesPage], ["分享", SharesPage],
  ["后台任务", AdminTasksPage], ["订单", OrdersPage], ["事件", EventsPage],
  ["滥用举报", ReportsPage], ["OAuth 应用", OAuthAppsPage]
] as const;

describe("管理后台前端原型", () => {
  it.each(pages)("%s 页展示标题和明确的演示提示", async (title, Page) => {
    render(<AdminDataSourceProvider><Page /></AdminDataSourceProvider>);
    expect(screen.getByRole("heading", { name: title, level: 2 })).toBeInTheDocument();
    expect(screen.getByText("前端演示模式")).toBeInTheDocument();
    expect(screen.queryByText("敬请期待")).not.toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText("正在加载演示数据…")).not.toBeInTheDocument());
  });

  it("Mock 数据默认空、操作可读回，且不会跨实例持久化", async () => {
    const source = new MockAdminDataSource();
    expect((await source.list("users")).items).toEqual([]);
    const row = await source.create<{ id: string | number; username: string }>("users", { username: "demo" });
    expect((await source.list("users")).items).toEqual([row]);
    await source.update<{ id: string | number; username: string }>("users", row.id, { username: "updated" });
    expect((await source.list("users")).items[0]).toMatchObject({ username: "updated" });
    await source.remove("users", row.id);
    expect((await source.list("users")).items).toEqual([]);
    expect((await new MockAdminDataSource().list("users")).items).toEqual([]);
  });

  it("存储策略支持创建、启用确认及备份标签", async () => {
    const user = userEvent.setup();
    render(<AdminDataSourceProvider><StoragePoliciesPage /></AdminDataSourceProvider>);
    await user.click(screen.getByRole("button", { name: /添加演示策略/ }));
    await user.type(screen.getByRole("textbox", { name: "策略名称" }), "演示归档");
    await user.type(screen.getByRole("textbox", { name: /端点或路径/ }), "/demo/archive");
    await user.click(screen.getByRole("button", { name: "保存到演示数据" }));
    expect(await screen.findByText("演示归档")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "启用" }));
    expect(screen.getByText("此操作仅改变页面内存中的状态，不影响真实存储服务。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "确认启用" }));
    expect(await screen.findByText("已启用")).toBeInTheDocument();
    await user.click(screen.getByRole("tab", { name: "备份历史" }));
    expect(screen.getByText("暂无备份历史")).toBeInTheDocument();
  });

  it("事件详情将载荷作为纯文本展示", async () => {
    const user = userEvent.setup();
    const event = { id: "event-1", channel: "system", level: "警告", type: "demo", subject: "test", summary: "安全示例", payload: "<script>alert(1)</script>", occurredAt: "2026-09-13T00:00:00Z" };
    const source = new MockAdminDataSource({ events: [event] });
    render(<AdminDataSourceProvider dataSource={source}><EventsPage /></AdminDataSourceProvider>);
    await screen.findByText("安全示例");
    await user.click(screen.getByRole("button", { name: "详情" }));
    expect(screen.getByText("<script>alert(1)</script>")).toBeInTheDocument();
    expect(document.querySelector("script")).toBeNull();
  });

  it("用户列表在 25 条 fixture 下分页并支持筛选", async () => {
    const user = userEvent.setup();
    const users = Array.from({ length: 25 }, (_, index) => ({ id: index + 1, username: `demo_${index + 1}`, nickname: `演示用户${index + 1}`, email: "", role: "USER", group: "演示组", status: "启用", storage: "0 B" }));
    render(<AdminDataSourceProvider dataSource={new MockAdminDataSource({ users })}><UsersPage /></AdminDataSourceProvider>);
    await screen.findByText("演示用户20");
    expect(screen.queryByText("演示用户21")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "下一页" }));
    expect(screen.getByText("演示用户21")).toBeInTheDocument();
    await user.type(screen.getByRole("searchbox", { name: "搜索" }), "demo_24");
    expect(screen.getByText("演示用户24")).toBeInTheDocument();
    expect(screen.queryByText("演示用户21")).not.toBeInTheDocument();
  });

  it("列表数据源出错时显示可重试错误状态", async () => {
    class FailingSource extends MockAdminDataSource {
      override async list<T extends { id: string | number }>(): Promise<{ items: T[]; total: number; mode: "mock" }> { throw new Error("演示查询失败"); }
    }
    render(<AdminDataSourceProvider dataSource={new FailingSource()}><NodesPage /></AdminDataSourceProvider>);
    expect(await screen.findByRole("alert")).toHaveTextContent("演示查询失败");
    expect(screen.getByRole("button", { name: "重试" })).toBeInTheDocument();
  });

  it("撤销演示分享只修改状态，不删除记录", async () => {
    const user = userEvent.setup();
    const share = { id: "s-1", shareId: "demo-share", file: "文档.pdf", owner: "演示用户", views: 0, downloads: 0, expiresAt: "", status: "有效" };
    render(<AdminDataSourceProvider dataSource={new MockAdminDataSource({ shares: [share] })}><SharesPage /></AdminDataSourceProvider>);
    await screen.findByText("demo-share");
    await user.click(screen.getByRole("button", { name: "撤销" }));
    await user.click(screen.getByRole("button", { name: "确认撤销" }));
    expect(screen.getByText("demo-share")).toBeInTheDocument();
    expect(within(screen.getByRole("row", { name: /demo-share/ })).getByText("已撤销")).toBeInTheDocument();
  });

  it("失败任务重试需要确认，确认后状态转为等待中", async () => {
    const user = userEvent.setup();
    const task = { id: "t-1", taskId: "demo-task", type: "RAG", content: "演示索引", status: "失败", creator: "demo", node: "", updatedAt: "" };
    render(<AdminDataSourceProvider dataSource={new MockAdminDataSource({ tasks: [task] })}><AdminTasksPage /></AdminDataSourceProvider>);
    await screen.findByText("demo-task");
    await user.click(screen.getByRole("button", { name: "重试" }));
    expect(screen.getByRole("dialog")).toHaveTextContent("不会执行真实业务动作");
    await user.click(screen.getByRole("button", { name: "确认重试" }));
    expect(within(screen.getByRole("row", { name: /demo-task/ })).getByText("等待中")).toBeInTheDocument();
  });

  it("参数设置中的下拉修改进入脏状态，放弃时需要确认", async () => {
    const user = userEvent.setup();
    render(<AdminDataSourceProvider><SettingsPage /></AdminDataSourceProvider>);
    await user.click(screen.getByRole("tab", { name: "用户会话" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "并发会话" }), "single");
    expect(screen.getByRole("button", { name: "保存演示配置" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "放弃修改" }));
    expect(screen.getByRole("dialog")).toHaveTextContent("放弃未保存的演示修改");
    await user.click(screen.getByRole("button", { name: "确认放弃" }));
    expect(screen.getByRole("combobox", { name: "并发会话" })).toHaveValue("allow");
  });

  it("OAuth 应用创建后只显示一次无效的演示 Secret", async () => {
    const user = userEvent.setup();
    render(<AdminDataSourceProvider><OAuthAppsPage /></AdminDataSourceProvider>);
    await user.click(screen.getByRole("button", { name: "创建演示应用" }));
    await user.type(screen.getByRole("textbox", { name: "应用名称" }), "演示客户端");
    await user.type(screen.getByRole("textbox", { name: /Client ID/ }), "demo-client");
    await user.type(screen.getByRole("textbox", { name: "所有者" }), "演示用户");
    await user.type(screen.getByRole("textbox", { name: "Scopes" }), "files.read");
    await user.type(screen.getByRole("textbox", { name: "回调地址" }), "https://example.invalid/callback");
    await user.selectOptions(screen.getByRole("combobox", { name: "状态" }), "启用");
    await user.click(screen.getByRole("button", { name: "保存到演示数据" }));
    expect(await screen.findByRole("dialog", { name: "一次性演示 Secret" })).toHaveTextContent("demo-only-not-a-real-secret");
    await user.click(screen.getByRole("button", { name: "关闭对话框" }));
    expect(screen.queryByText("demo-only-not-a-real-secret")).not.toBeInTheDocument();
  });

  it("编辑用户组不会清零派生成员数", async () => {
    const user = userEvent.setup();
    const group = { id: "g-1", name: "演示组", description: "测试", policy: "默认策略", quota: "10 GB", users: 5, system: "自定义组" };
    render(<AdminDataSourceProvider dataSource={new MockAdminDataSource({ groups: [group] })}><UserGroupsPage /></AdminDataSourceProvider>);
    await screen.findByText("演示组");
    await user.click(screen.getByRole("button", { name: "编辑" }));
    await user.clear(screen.getByRole("textbox", { name: "用户组名称" }));
    await user.type(screen.getByRole("textbox", { name: "用户组名称" }), "新组名");
    await user.click(screen.getByRole("button", { name: "保存到演示数据" }));
    const row = screen.getByRole("row", { name: /新组名/ });
    expect(within(row).getByText("5")).toBeInTheDocument();
  });

  it("订单可按类型和日期组合筛选", async () => {
    const user = userEvent.setup();
    const order = { id: "o-1", orderNo: "demo-order", user: "demo", orderType: "存储套餐", product: "扩容", amountCents: 0, status: "待支付", createdAt: "2026-09-13T00:00:00Z" };
    render(<AdminDataSourceProvider dataSource={new MockAdminDataSource({ orders: [order] })}><OrdersPage /></AdminDataSourceProvider>);
    await screen.findByText("demo-order");
    await user.selectOptions(screen.getByRole("combobox", { name: "类型" }), "增值服务");
    expect(screen.queryByText("demo-order")).not.toBeInTheDocument();
    await user.selectOptions(screen.getByRole("combobox", { name: "类型" }), "存储套餐");
    await user.type(screen.getByLabelText("开始日期"), "2026-09-14");
    expect(screen.queryByText("demo-order")).not.toBeInTheDocument();
  });

  it("后台任务详情包含演示时间线", async () => {
    const user = userEvent.setup();
    const task = { id: "t-2", taskId: "demo-timeline", type: "文件", content: "演示处理", status: "成功", creator: "demo", node: "node-1", createdAt: "2026-09-13T00:00:00Z", updatedAt: "2026-09-13T00:01:00Z" };
    render(<AdminDataSourceProvider dataSource={new MockAdminDataSource({ tasks: [task] })}><AdminTasksPage /></AdminDataSourceProvider>);
    await screen.findByText("demo-timeline");
    await user.click(screen.getByRole("button", { name: "详情" }));
    expect(screen.getByRole("heading", { name: "演示时间线" })).toBeInTheDocument();
    expect(screen.getByText("进入队列")).toBeInTheDocument();
  });
});
