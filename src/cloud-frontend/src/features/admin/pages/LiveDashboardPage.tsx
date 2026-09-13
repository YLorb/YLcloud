import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../../../api";
import { Button } from "../../../components/ui/Button";
import { AdminPage, AdminSection, AdminSelect, AdminStat, AdminStats, AdminUnavailableHint } from "../components/AdminPage";
import { AdminTable } from "../components/AdminTable";

export function LiveDashboardPage() {
  const [days, setDays] = useState(7);
  const metrics = useQuery({ queryKey: ["admin-daily-metrics", days], queryFn: () => api.adminDailyMetrics(days) });
  const totals = metrics.data?.days.reduce((sum, day) => ({ files: sum.files + day.newFiles, blobs: sum.blobs + day.newBlobs, users: sum.users + day.newUsers, spaces: sum.spaces + day.newSpaces }), { files: 0, blobs: 0, users: 0, spaces: 0 });
  return <AdminPage eyebrow="管理后台" title="面板首页" description="按 MySQL 现有权威记录的创建时间统计；文件引用与 Blob 实体分别计数，目录不计入文件数。"
    actions={<div className="button-row"><AdminSelect label="统计时间范围" value={String(days)} onChange={(value) => setDays(Number(value))} options={[{ value: "1", label: "今天" }, { value: "7", label: "最近 7 天" }, { value: "30", label: "最近 30 天" }]} /><Button disabled={metrics.isFetching} onClick={() => void metrics.refetch()}>刷新</Button></div>}>
    <AdminStats><AdminStat label="新增文件数" value={totals?.files ?? "—"} detail="个人与 Space 文件引用" /><AdminStat label="新增 Blob" value={totals?.blobs ?? "—"} detail="file_info 实体记录" /><AdminStat label="新增用户数" value={totals?.users ?? "—"} detail="用户创建记录" /><AdminStat label="新增 Space" value={totals?.spaces ?? "—"} detail="Space 创建记录" /><AdminStat label="Token 消耗量" value="—" detail="统计尚未接入" /></AdminStats>
    <AdminSection title="每日活动" description="Asia/Shanghai 自然日，包含今天。历史已物理删除的记录无法从当前表还原，因此不是不可变审计计数。">
      <AdminTable items={metrics.data?.days || []} getKey={(day) => day.date} loading={metrics.isPending} error={metrics.isError ? metrics.error.message : null} onRetry={() => void metrics.refetch()} emptyTitle="暂无统计数据" emptyMessage="等待服务器返回统计记录。" columns={[
        { key: "date", label: "日期", render: (day) => day.date }, { key: "files", label: "新增文件引用", render: (day) => day.newFiles }, { key: "blobs", label: "新增 Blob", render: (day) => day.newBlobs }, { key: "users", label: "新增用户", render: (day) => day.newUsers }, { key: "spaces", label: "新增 Space", render: (day) => day.newSpaces }
      ]} />
    </AdminSection>
    <AdminUnavailableHint>节点负载、存储容量及 Token 消耗暂无真实统计，不以零值表示正常状态。</AdminUnavailableHint>
  </AdminPage>;
}
