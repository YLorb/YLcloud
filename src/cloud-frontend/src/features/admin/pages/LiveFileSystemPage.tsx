import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../../../api";
import { Button } from "../../../components/ui/Button";
import { Dialog } from "../../../components/ui/Dialog";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../../../components/ui/Tabs";
import type { AdminFullTextSearchFileHit } from "../../../types";
import { AdminField } from "../components/AdminDialogs";
import { AdminPage, AdminUnavailableHint } from "../components/AdminPage";
import { AdminPagination, AdminTable } from "../components/AdminTable";

export function LiveFileSystemPage() {
  const [query, setQuery] = useState("");
  const [space, setSpace] = useState("");
  const [submitted, setSubmitted] = useState<{ query: string; spaceId?: number } | null>(null);
  const [page, setPage] = useState(1);
  const [detail, setDetail] = useState<AdminFullTextSearchFileHit | null>(null);
  const validSpace = !space || (/^[1-9]\d*$/.test(space) && Number.isSafeInteger(Number(space)));
  const result = useQuery({ queryKey: ["admin-fulltext", submitted, page], queryFn: () => api.adminFullTextSearch({ ...submitted!, page, pageSize: 20 }), enabled: submitted !== null });
  return <AdminPage eyebrow="文件能力" title="文件系统" description="检索已索引 Space 文件及 Chunk 证据；不是全站文件清单，也不代表完整的存储实体扫描。">
    <Tabs defaultValue="search"><TabsList aria-label="文件系统功能"><TabsTrigger value="search">全文搜索</TabsTrigger><TabsTrigger value="parameters">文件参数</TabsTrigger><TabsTrigger value="icons">文件图标</TabsTrigger><TabsTrigger value="apps">预览应用</TabsTrigger></TabsList>
      <TabsContent value="search"><form className="admin-filter-toolbar" onSubmit={(event) => { event.preventDefault(); if (query.trim() && validSpace) { setPage(1); setSubmitted({ query: query.trim(), spaceId: space ? Number(space) : undefined }); } }}>
        <AdminField label="搜索内容"><input required value={query} maxLength={500} onChange={(event) => setQuery(event.target.value)} /></AdminField><AdminField label="Space ID（可选）"><input value={space} inputMode="numeric" onChange={(event) => setSpace(event.target.value)} /></AdminField><Button type="submit" variant="primary" disabled={!query.trim() || !validSpace || result.isFetching}>搜索</Button>
      </form>{!validSpace && <p role="alert">Space ID 必须为正整数。</p>}
        <AdminUnavailableHint>检索方式由服务端决定，当前最多返回 50 个候选结果；不提供未被后端支持的检索模式开关。</AdminUnavailableHint>
        {result.data && <p>当前候选结果 {result.data.total} 条 · 服务端耗时 {result.data.tookMs} ms</p>}
        <AdminTable items={result.data?.files || []} getKey={(file) => `${file.spaceId}:${file.spaceFileId}:${file.documentId}`} loading={submitted !== null && result.isPending} error={result.isError ? result.error.message : null} onRetry={() => void result.refetch()} emptyTitle={submitted ? "未找到匹配文件" : "输入内容开始搜索"} emptyMessage="仅展示后端返回的检索结果，不填充演示记录。" columns={[
          { key: "file", label: "文件", render: (file) => file.fileName }, { key: "space", label: "Space", render: (file) => file.spaceName || file.spaceId },
          { key: "path", label: "路径", render: (file) => file.path || "—" }, { key: "match", label: "匹配方式", render: (file) => file.matchType },
          { key: "chunks", label: "Chunk 数", render: (file) => file.chunkCount }, { key: "evidence", label: "证据", render: (file) => <Button size="sm" onClick={() => setDetail(file)}>查看证据</Button> }
        ]} /><AdminPagination page={page} total={result.data?.total || 0} onPageChange={setPage} />
      </TabsContent>
      <TabsContent value="parameters"><AdminUnavailableHint>已有文件配置可在“参数设置 → 服务端配置”修改；本页不重复提供模拟保存。</AdminUnavailableHint></TabsContent>
      <TabsContent value="icons"><AdminUnavailableHint>图标映射尚无后端配置接口。</AdminUnavailableHint></TabsContent>
      <TabsContent value="apps"><AdminUnavailableHint>预览应用配置尚无后端接口。</AdminUnavailableHint></TabsContent>
    </Tabs>
    <Dialog open={detail !== null} onOpenChange={(open) => !open && setDetail(null)} title="检索证据" description={detail?.fileName || "Chunk 原文片段"} size="lg">
      {detail?.evidences.map((evidence) => <section key={evidence.chunkId}><h3>Chunk {evidence.chunkId}</h3><p style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>{evidence.content}</p>{evidence.score != null && <small>得分：{evidence.score}</small>}</section>)}
    </Dialog>
  </AdminPage>;
}
