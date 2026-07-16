import * as Select from "@radix-ui/react-select";
import * as Switch from "@radix-ui/react-switch";
import * as Tabs from "@radix-ui/react-tabs";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Activity, BarChart3, Check, ChevronDown, Database, FileCheck2, FileText, Pencil, RefreshCw, RotateCcw, Search, Settings2, ShieldCheck, Sparkles, Tags, TriangleAlert, WandSparkles } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { useSearchParams } from "react-router-dom";
import { toast } from "sonner";
import { api } from "../../api";
import { Button } from "../../components/ui/Button";
import { Dialog } from "../../components/ui/Dialog";
import { EmptyState, ErrorState, LoadingState } from "../../components/ui/PageState";
import { StatusBadge, type StatusTone } from "../../components/ui/StatusBadge";
import { formatTime } from "../../fileUtils";
import type { KnowledgeDocument, KnowledgePipelineTask, KnowledgeProfile, RagConfig } from "../../types";

const pipelineStages = ["文档入库", "解析", "切分", "Embedding", "索引", "知识画像"];

export function KnowledgePage() {
  const client = useQueryClient();
  const [params, setParams] = useSearchParams();
  const spaces = useQuery({ queryKey: ["spaces"], queryFn: api.listSpaces });
  const requestedSpace = Number(params.get("space") || 0);
  const spaceId = requestedSpace || spaces.data?.[0]?.id || 0;
  const tab = params.get("tab") || "overview";

  useEffect(() => {
    if (!requestedSpace && spaces.data?.[0]) setParams((current) => { const next = new URLSearchParams(current); next.set("space", String(spaces.data![0].id)); return next; }, { replace: true });
  }, [requestedSpace, setParams, spaces.data]);

  function chooseSpace(value: string) { setParams((current) => { const next = new URLSearchParams(current); next.set("space", value); return next; }); }
  function chooseTab(value: string) { setParams((current) => { const next = new URLSearchParams(current); next.set("tab", value); return next; }); }

  if (spaces.isLoading) return <LoadingState label="正在加载知识库" />;
  if (spaces.isError) return <ErrorState message={spaces.error instanceof Error ? spaces.error.message : "无法加载空间"} onRetry={() => spaces.refetch()} />;
  if (!spaceId) return <EmptyState title="需要先创建团队空间" message="知识库以团队空间为边界。创建空间并上传文档后即可构建索引。" />;

  return (
    <div className="knowledge-page">
      <section className="knowledge-context-card">
        <div><span className="section-eyebrow">当前知识库</span><Select.Root value={String(spaceId)} onValueChange={chooseSpace}><Select.Trigger className="select-trigger"><Select.Value /><Select.Icon><ChevronDown size={16} /></Select.Icon></Select.Trigger><Select.Portal><Select.Content className="select-content" position="popper"><Select.Viewport>{spaces.data?.map((space) => <Select.Item className="select-item" key={space.id} value={String(space.id)}><Select.ItemText>{space.name}</Select.ItemText><Select.ItemIndicator><Check size={14} /></Select.ItemIndicator></Select.Item>)}</Select.Viewport></Select.Content></Select.Portal></Select.Root></div>
        <div className="pipeline-mini" aria-label="知识库处理流程">{pipelineStages.map((stage, index) => <span key={stage} className={index === pipelineStages.length - 1 ? "pipeline-mini__optional" : ""}>{stage}{index < pipelineStages.length - 1 && <i>→</i>}</span>)}</div>
      </section>
      <Tabs.Root className="tabs-root" value={tab} onValueChange={chooseTab}>
        <Tabs.List className="tabs-list" aria-label="知识库功能"><Tabs.Trigger value="overview"><BarChart3 size={16} />概览</Tabs.Trigger><Tabs.Trigger value="documents"><FileText size={16} />文档</Tabs.Trigger><Tabs.Trigger value="profiles"><Tags size={16} />知识画像</Tabs.Trigger><Tabs.Trigger value="pipeline"><Activity size={16} />处理任务</Tabs.Trigger><Tabs.Trigger value="analytics"><Database size={16} />检索分析</Tabs.Trigger><Tabs.Trigger value="settings"><Settings2 size={16} />配置</Tabs.Trigger></Tabs.List>
        <Tabs.Content value="overview" className="tab-panel"><Overview spaceId={spaceId} /></Tabs.Content>
        <Tabs.Content value="documents" className="tab-panel"><Documents spaceId={spaceId} /></Tabs.Content>
        <Tabs.Content value="profiles" className="tab-panel"><ProfileWorkspace spaceId={spaceId} /></Tabs.Content>
        <Tabs.Content value="pipeline" className="tab-panel"><Pipeline spaceId={spaceId} /></Tabs.Content>
        <Tabs.Content value="analytics" className="tab-panel"><Analytics spaceId={spaceId} /></Tabs.Content>
        <Tabs.Content value="settings" className="tab-panel"><KnowledgeSettings spaceId={spaceId} onSaved={() => client.invalidateQueries({ queryKey: ["rag-config", spaceId] })} /></Tabs.Content>
      </Tabs.Root>
    </div>
  );
}

function Overview({ spaceId }: { spaceId: number }) {
  const dashboard = useQuery({ queryKey: ["knowledge-dashboard", spaceId], queryFn: () => api.knowledgeDashboard(spaceId), refetchInterval: (query) => query.state.data?.runningTaskCount ? 5_000 : false });
  if (dashboard.isLoading) return <LoadingState label="正在汇总知识库状态" />;
  if (dashboard.isError) return <ErrorState message={errorMessage(dashboard.error)} onRetry={() => dashboard.refetch()} />;
  const data = dashboard.data!;
  return <div className="overview-grid"><div className="metrics-grid"><Metric icon={<FileText />} label="文档总数" value={data.documentCount} /><Metric icon={<FileCheck2 />} label="已完成索引" value={data.indexedCount} tone="success" /><Metric icon={<Sparkles />} label="已生成画像" value={data.profiledCount} /><Metric icon={<TriangleAlert />} label="失败任务" value={data.failedTaskCount} tone={data.failedTaskCount ? "danger" : "neutral"} /></div><section className="panel"><header className="panel-header"><div><span className="section-eyebrow">运行状态</span><h2>处理队列</h2></div>{data.runningTaskCount ? <StatusBadge tone="running">正在处理 {data.runningTaskCount} 项</StatusBadge> : <StatusBadge tone="success">队列正常</StatusBadge>}</header><div className="summary-list"><div><span>等待处理</span><strong>{data.pendingTaskCount}</strong></div><div><span>需要人工复核</span><strong>{data.needsReviewCount}</strong></div><div><span>平均画像质量</span><strong>{Math.round(data.averageQualityScore || 0)}</strong></div></div></section><section className="panel"><header className="panel-header"><div><span className="section-eyebrow">分类覆盖</span><h2>知识分布</h2></div></header><div className="facet-cloud">{(data.categories || []).length ? data.categories!.map((facet) => <span key={facet.name}>{facet.name}<b>{facet.count}</b></span>) : <small>暂无分类数据</small>}</div></section></div>;
}

function Documents({ spaceId }: { spaceId: number }) {
  const client = useQueryClient();
  const [query, setQuery] = useState("");
  const docs = useQuery({ queryKey: ["rag-documents", spaceId], queryFn: () => api.listRagDocuments(spaceId) });
  const visible = useMemo(() => (docs.data || []).filter((doc) => doc.fileName.toLocaleLowerCase().includes(query.toLocaleLowerCase())), [docs.data, query]);
  const rebuild = useMutation({ mutationFn: (fileId: number) => api.rebuildFileRag(spaceId, fileId), onSuccess: () => { client.invalidateQueries({ queryKey: ["rag-documents", spaceId] }); toast.success("已重新提交文档处理"); }, onError: showError });
  const repair = useMutation({ mutationFn: (fileId: number) => api.repairFileVectors(spaceId, fileId), onSuccess: () => { client.invalidateQueries({ queryKey: ["rag-documents", spaceId] }); toast.success("已提交向量修复"); }, onError: showError });
  if (docs.isLoading) return <LoadingState label="正在加载知识库文档" />;
  if (docs.isError) return <ErrorState message={errorMessage(docs.error)} onRetry={() => docs.refetch()} />;
  return <><div className="content-toolbar"><label className="search-box"><Search size={17} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索文档" /></label><Button variant="ghost" size="icon" aria-label="刷新" onClick={() => docs.refetch()}><RefreshCw size={17} /></Button></div>{visible.length === 0 ? <EmptyState title="没有知识库文档" message="请先在团队空间中上传文档。只有索引事务成功后文档才会显示为可检索。" /> : <div className="data-table-wrap"><table className="data-table"><thead><tr><th>文档</th><th>索引状态</th><th>切片数</th><th>更新时间</th><th><span className="sr-only">操作</span></th></tr></thead><tbody>{visible.map((doc) => { const status = statusPresentation(doc.indexStatus); return <tr key={doc.id}><td><span className="file-name"><FileText size={18} /><span><strong>{doc.fileName}</strong>{doc.errorMessage && <small className="inline-error">{doc.errorMessage}</small>}</span></span></td><td><StatusBadge tone={status.tone}>{status.label}</StatusBadge></td><td>{doc.chunkCount ?? "—"}</td><td>{formatTime(doc.updatetime)}</td><td><div className="row-actions"><Button size="sm" loading={repair.isPending && repair.variables === doc.spaceFileId} onClick={() => repair.mutate(doc.spaceFileId)}><WandSparkles size={15} />修复向量</Button><Button size="sm" variant={status.tone === "danger" ? "danger" : "secondary"} loading={rebuild.isPending && rebuild.variables === doc.spaceFileId} onClick={() => rebuild.mutate(doc.spaceFileId)}><RotateCcw size={15} />重新处理</Button></div></td></tr>; })}</tbody></table></div>}</>;
}

function ProfileWorkspace({ spaceId }: { spaceId: number }) {
  const client = useQueryClient();
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState<KnowledgeDocument | null>(null);
  const documents = useQuery({ queryKey: ["knowledge-documents", spaceId], queryFn: () => api.listKnowledgeDocuments(spaceId) });
  const categories = useQuery({ queryKey: ["knowledge-categories", spaceId], queryFn: () => api.listKnowledgeCategories(spaceId) });
  const visible = useMemo(() => (documents.data || []).filter((document) => `${document.fileName} ${document.title || ""} ${document.category || ""}`.toLocaleLowerCase().includes(query.toLocaleLowerCase())), [documents.data, query]);
  const regenerate = useMutation({ mutationFn: (documentId: number) => api.regenerateKnowledgeProfile(spaceId, documentId), onSuccess: () => { client.invalidateQueries({ queryKey: ["knowledge-documents", spaceId] }); toast.success("知识画像已重新提交"); }, onError: showError });
  const reclassify = useMutation({ mutationFn: (documentId: number) => api.reclassifyKnowledgeDocument(spaceId, documentId), onSuccess: () => { client.invalidateQueries({ queryKey: ["knowledge-documents", spaceId] }); toast.success("文档已重新分类"); }, onError: showError });
  const review = useMutation({ mutationFn: (documentId: number) => api.markKnowledgeProfileReviewed(spaceId, documentId), onSuccess: () => { client.invalidateQueries({ queryKey: ["knowledge-documents", spaceId] }); toast.success("画像已标记为人工复核通过"); }, onError: showError });
  if (documents.isLoading) return <LoadingState label="正在加载知识画像" />;
  if (documents.isError) return <ErrorState message={errorMessage(documents.error)} onRetry={() => documents.refetch()} />;
  return <><div className="content-toolbar"><label className="search-box"><Search size={17} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索标题、分类或文件名" /></label><div className="facet-cloud">{(categories.data || []).slice(0, 5).map((facet) => <span key={facet.name}>{facet.name}<b>{facet.count}</b></span>)}</div></div>{visible.length === 0 ? <EmptyState title="暂无知识画像" message="启用知识画像后，系统会在 RAG 索引成功后生成文档摘要、分类、标签和关键词。" /> : <div className="profile-grid">{visible.map((document) => { const status = statusPresentation(document.profileStatus); return <article className="profile-card" key={document.documentId}><header><span><FileText size={17} /></span><div><strong>{document.title || document.fileName}</strong><small>{document.fileName}</small></div><StatusBadge tone={status.tone}>{status.label}</StatusBadge></header><p>{document.summary || "尚未生成摘要。"}</p><div className="profile-tags"><span>{document.category || "未分类"}</span>{(document.tags || []).slice(0, 3).map((tag) => <span key={tag}>#{tag}</span>)}</div><footer><span>质量分 <b>{Math.round(document.qualityScore || 0)}</b></span><div className="row-actions"><Button variant="ghost" size="icon" aria-label="编辑画像" onClick={() => setSelected(document)}><Pencil size={15} /></Button><Button variant="ghost" size="icon" aria-label="重新分类" onClick={() => reclassify.mutate(document.documentId)}><Tags size={15} /></Button><Button variant="ghost" size="icon" aria-label="重新生成画像" onClick={() => regenerate.mutate(document.documentId)}><Sparkles size={15} /></Button>{document.reviewStatus !== "REVIEWED" && <Button variant="confirm" size="sm" onClick={() => review.mutate(document.documentId)}><ShieldCheck size={14} />复核通过</Button>}</div></footer>{document.errorMessage && <div className="error-banner">{document.errorMessage}</div>}</article>; })}</div>}<Dialog open={Boolean(selected)} onOpenChange={(open) => !open && setSelected(null)} title="编辑知识画像" description={selected?.fileName}><ProfileEditor spaceId={spaceId} document={selected} onClose={() => { setSelected(null); client.invalidateQueries({ queryKey: ["knowledge-documents", spaceId] }); }} /></Dialog></>;
}

function ProfileEditor({ spaceId, document, onClose }: { spaceId: number; document: KnowledgeDocument | null; onClose: () => void }) {
  const profile = useQuery({ queryKey: ["knowledge-profile", spaceId, document?.documentId], queryFn: () => api.getKnowledgeProfile(spaceId, document!.documentId), enabled: Boolean(document) });
  const [draft, setDraft] = useState<Partial<KnowledgeProfile>>({});
  useEffect(() => { if (profile.data) setDraft(profile.data); }, [profile.data]);
  const save = useMutation({ mutationFn: () => api.updateKnowledgeProfile(spaceId, document!.documentId, { title: draft.title, summary: draft.summary, category: draft.category, tags: draft.tags, keywords: draft.keywords, questions: draft.questions, profileStatus: draft.profileStatus }), onSuccess: () => { toast.success("知识画像已保存"); onClose(); }, onError: showError });
  if (profile.isLoading) return <LoadingState label="正在加载画像详情" />;
  if (profile.isError) return <ErrorState message={errorMessage(profile.error)} onRetry={() => profile.refetch()} />;
  const setList = (key: "tags" | "keywords" | "questions", value: string) => setDraft((current) => ({ ...current, [key]: value.split(/[,，\n]/).map((item) => item.trim()).filter(Boolean) }));
  return <div className="form-stack"><label className="field"><span>标题</span><input value={draft.title || ""} onChange={(event) => setDraft((current) => ({ ...current, title: event.target.value }))} /></label><label className="field"><span>摘要</span><textarea rows={5} value={draft.summary || ""} onChange={(event) => setDraft((current) => ({ ...current, summary: event.target.value }))} /></label><div className="form-grid"><label className="field"><span>分类</span><input value={draft.category || ""} onChange={(event) => setDraft((current) => ({ ...current, category: event.target.value }))} /></label><label className="field"><span>标签（逗号分隔）</span><input value={(draft.tags || []).join(", ")} onChange={(event) => setList("tags", event.target.value)} /></label></div><label className="field"><span>关键词（逗号分隔）</span><input value={(draft.keywords || []).join(", ")} onChange={(event) => setList("keywords", event.target.value)} /></label><label className="field"><span>建议问题（每行一个）</span><textarea rows={4} value={(draft.questions || []).join("\n")} onChange={(event) => setList("questions", event.target.value)} /></label><div className="dialog-footer-inline"><Button onClick={onClose}>取消</Button><Button variant="confirm" loading={save.isPending} onClick={() => save.mutate()}><Check size={16} />保存画像</Button></div></div>;
}

function Pipeline({ spaceId }: { spaceId: number }) {
  const client = useQueryClient();
  const config = useQuery({ queryKey: ["rag-config", spaceId], queryFn: () => api.ragConfig(spaceId) });
  const ragTasks = useQuery({ queryKey: ["rag-tasks", spaceId], queryFn: () => api.listRagTasks(spaceId), refetchInterval: 5_000 });
  const profileTasks = useQuery({ queryKey: ["knowledge-tasks", spaceId], queryFn: () => api.listKnowledgeTasks(spaceId), refetchInterval: 5_000 });
  const rebuild = useMutation({ mutationFn: () => api.rebuildSpaceRag(spaceId), onSuccess: () => { refreshTasks(); toast.success("已提交 RAG 索引重建"); }, onError: showError });
  const runProfiles = useMutation({ mutationFn: () => api.runKnowledgePipeline(spaceId), onSuccess: () => { refreshTasks(); toast.success("已提交知识画像任务"); }, onError: showError });
  const retryRag = useMutation({ mutationFn: (taskId: number) => api.retryRagTask(spaceId, taskId), onSuccess: () => { refreshTasks(); toast.success("RAG 任务已重新提交"); }, onError: showError });
  const retryProfile = useMutation({ mutationFn: (taskId: number) => api.retryKnowledgeTask(spaceId, taskId), onSuccess: () => { refreshTasks(); toast.success("知识画像任务已重新提交"); }, onError: showError });
  const retryFailedRag = useMutation({ mutationFn: () => api.retryFailedRagTasks(spaceId), onSuccess: () => { refreshTasks(); toast.success("失败的 RAG 任务已批量重试"); }, onError: showError });
  const retryFailedProfiles = useMutation({ mutationFn: () => api.retryFailedKnowledgeTasks(spaceId), onSuccess: () => { refreshTasks(); toast.success("失败的画像任务已批量重试"); }, onError: showError });
  const repairVectors = useMutation({ mutationFn: () => api.repairSpaceVectors(spaceId), onSuccess: () => { refreshTasks(); toast.success("已提交空间向量一致性修复"); }, onError: showError });
  function refreshTasks() { client.invalidateQueries({ queryKey: ["rag-tasks", spaceId] }); client.invalidateQueries({ queryKey: ["knowledge-tasks", spaceId] }); }
  if (ragTasks.isLoading || profileTasks.isLoading || config.isLoading) return <LoadingState label="正在加载处理任务" />;
  if (ragTasks.isError || profileTasks.isError || config.isError) return <ErrorState message={errorMessage(ragTasks.error || profileTasks.error || config.error)} onRetry={() => { ragTasks.refetch(); profileTasks.refetch(); config.refetch(); }} />;
  const profileEnabled = config.data?.knowledgeProfileEnabled !== 0;
  return <div className="pipeline-page"><section className="transaction-card"><header><div><span className="section-eyebrow">核心事务</span><h2>RAG 索引构建</h2><p>文档入库 → 解析 → 切分 → Embedding → 索引。任一步失败都不应把文档标记为可用。</p></div><div className="toolbar-actions"><Button loading={repairVectors.isPending} onClick={() => repairVectors.mutate()}><WandSparkles size={16} />修复向量</Button><Button variant="danger" loading={retryFailedRag.isPending} onClick={() => retryFailedRag.mutate()}><RotateCcw size={16} />重试失败项</Button><Button variant="confirm" loading={rebuild.isPending} onClick={() => rebuild.mutate()}><RefreshCw size={16} />重建索引</Button></div></header><div className="stage-flow">{pipelineStages.slice(0, 5).map((stage, index) => <span key={stage}><i>{index + 1}</i>{stage}</span>)}</div></section><section className="transaction-card transaction-card--optional"><header><div><span className="section-eyebrow">可选增强</span><h2>知识画像</h2><p>仅在 RAG 索引成功且功能开启后运行，不影响文档是否完成 RAG 入库。</p></div>{profileEnabled ? <div className="toolbar-actions"><Button variant="danger" loading={retryFailedProfiles.isPending} onClick={() => retryFailedProfiles.mutate()}><RotateCcw size={16} />重试失败项</Button><Button variant="confirm" loading={runProfiles.isPending} onClick={() => runProfiles.mutate()}><Sparkles size={16} />生成画像</Button></div> : <StatusBadge tone="neutral">已关闭</StatusBadge>}</header></section><TaskSection title="RAG 主事务任务" tasks={(ragTasks.data || []).map((task) => ({ id: task.id, type: task.taskType, status: task.taskStatus, progress: task.totalCount ? Math.round(((task.successCount || 0) / task.totalCount) * 100) : undefined, success: task.successCount, failed: task.failedCount, error: task.errorMessage, time: task.updatetime }))} onRetry={(id) => retryRag.mutate(id)} retrying={retryRag.isPending} /><TaskSection title="知识画像任务" tasks={(profileTasks.data || []).map(toDisplayTask)} onRetry={(id) => retryProfile.mutate(id)} retrying={retryProfile.isPending} /></div>;
}

function Analytics({ spaceId }: { spaceId: number }) {
  const summary = useQuery({ queryKey: ["rag-analytics", spaceId], queryFn: () => api.ragAnalyticsSummary(spaceId) });
  const noAnswer = useQuery({ queryKey: ["rag-no-answer", spaceId], queryFn: () => api.ragAnalyticsNoAnswer(spaceId, 20) });
  if (summary.isLoading || noAnswer.isLoading) return <LoadingState label="正在加载检索分析" />;
  if (summary.isError || noAnswer.isError) return <ErrorState message={errorMessage(summary.error || noAnswer.error)} onRetry={() => { summary.refetch(); noAnswer.refetch(); }} />;
  const data = summary.data!;
  return <div className="analytics-layout"><div className="metrics-grid"><Metric label="查询总数" value={data.queryCount} /><Metric label="成功回答" value={data.successCount} tone="success" /><Metric label="无答案" value={data.noAnswerCount} tone={data.noAnswerCount ? "warning" : "neutral"} /><Metric label="引用覆盖率" value={`${Math.round((data.citationCoverage || 0) * (data.citationCoverage <= 1 ? 100 : 1))}%`} /></div><section className="panel"><header className="panel-header"><div><span className="section-eyebrow">质量观察</span><h2>无答案问题</h2></div></header>{(noAnswer.data || []).length === 0 ? <EmptyState title="暂时没有无答案记录" message="检索质量表现正常。" /> : <div className="summary-list">{noAnswer.data!.map((log) => <div key={log.id}><span>{log.question}</span><small>{formatTime(log.createtime)}</small></div>)}</div>}</section></div>;
}

function KnowledgeSettings({ spaceId, onSaved }: { spaceId: number; onSaved: () => void }) {
  const config = useQuery({ queryKey: ["rag-config", spaceId], queryFn: () => api.ragConfig(spaceId) });
  const [form, setForm] = useState<Partial<RagConfig>>({});
  useEffect(() => { if (config.data) setForm({ ...config.data, knowledgeProfileEnabled: config.data.knowledgeProfileEnabled ?? 1 }); }, [config.data]);
  const save = useMutation({ mutationFn: () => api.updateRagConfig(spaceId, form), onSuccess: () => { onSaved(); toast.success("知识库配置已保存"); }, onError: showError });
  if (config.isLoading) return <LoadingState label="正在加载知识库配置" />;
  if (config.isError) return <ErrorState message={errorMessage(config.error)} onRetry={() => config.refetch()} />;
  const updateNumber = (key: keyof RagConfig, value: string) => setForm((current) => ({ ...current, [key]: Number(value) }));
  return <div className="settings-form"><section className="settings-section"><div><span className="section-eyebrow">检索</span><h2>切片与召回</h2><p>配置会影响后续构建任务；已索引文档需重建才能完全应用。</p></div><div className="form-grid"><label className="field"><span>切片大小</span><input type="number" min={100} value={form.chunkSize ?? 500} onChange={(event) => updateNumber("chunkSize", event.target.value)} /></label><label className="field"><span>切片重叠</span><input type="number" min={0} value={form.chunkOverlap ?? 50} onChange={(event) => updateNumber("chunkOverlap", event.target.value)} /></label><label className="field"><span>Top K</span><input type="number" min={1} max={50} value={form.topK ?? 5} onChange={(event) => updateNumber("topK", event.target.value)} /></label><label className="field"><span>相关度阈值</span><input type="number" min={0} max={1} step={0.05} value={form.scoreThreshold ?? 0.3} onChange={(event) => updateNumber("scoreThreshold", event.target.value)} /></label></div></section><section className="settings-section"><div><span className="section-eyebrow">增强功能</span><h2>知识画像</h2><p>默认开启。RAG 索引完成后才会执行；关闭时不会启动后续画像步骤。</p></div><label className="switch-field"><Switch.Root className="switch-root" checked={(form.knowledgeProfileEnabled ?? 1) !== 0} onCheckedChange={(checked) => setForm((current) => ({ ...current, knowledgeProfileEnabled: checked ? 1 : 0 }))}><Switch.Thumb className="switch-thumb" /></Switch.Root><span>{(form.knowledgeProfileEnabled ?? 1) !== 0 ? "已开启" : "已关闭"}</span></label></section><div className="sticky-save"><span>保存后，新的处理任务将使用当前配置。</span><Button variant="confirm" loading={save.isPending} onClick={() => save.mutate()}><Check size={16} />保存配置</Button></div></div>;
}

function TaskSection({ title, tasks, onRetry, retrying }: { title: string; tasks: DisplayTask[]; onRetry: (id: number) => void; retrying: boolean }) {
  return <section className="panel"><header className="panel-header"><div><span className="section-eyebrow">任务明细</span><h2>{title}</h2></div></header>{tasks.length === 0 ? <EmptyState title="暂无任务" message="触发处理后，任务状态会显示在这里。" /> : <div className="data-table-wrap"><table className="data-table"><thead><tr><th>任务</th><th>状态</th><th>处理结果</th><th>更新时间</th><th><span className="sr-only">操作</span></th></tr></thead><tbody>{tasks.map((task) => { const status = statusPresentation(task.status); return <tr key={task.id}><td><strong>{task.type || `任务 #${task.id}`}</strong>{task.error && <small className="inline-error">{task.error}</small>}</td><td><StatusBadge tone={status.tone}>{status.label}</StatusBadge></td><td><span className="result-counts"><b className="success-text">成功 {task.success || 0}</b><b className={task.failed ? "danger-text" : "muted-text"}>失败 {task.failed || 0}</b></span></td><td>{formatTime(task.time)}</td><td>{status.tone === "danger" && <Button variant="danger" size="sm" loading={retrying} onClick={() => onRetry(task.id)}><RotateCcw size={15} />重试</Button>}</td></tr>; })}</tbody></table></div>}</section>;
}

type DisplayTask = { id: number; type?: string; status?: string; progress?: number; success?: number; failed?: number; error?: string; time?: string };
function toDisplayTask(task: KnowledgePipelineTask): DisplayTask { return { id: task.id, type: task.taskType, status: task.taskStatus, progress: task.progress, success: task.successCount, failed: task.failedCount, error: task.errorMessage, time: task.updatetime }; }
function Metric({ icon, label, value, tone = "neutral" }: { icon?: ReactNode; label: string; value: ReactNode; tone?: "success" | "danger" | "warning" | "neutral" }) { return <article className={`metric-card metric-card--${tone}`}><span>{icon}</span><div><small>{label}</small><strong>{value}</strong></div></article>; }
function statusPresentation(status?: string | number): { tone: StatusTone; label: string } { const value = String(status ?? "").toUpperCase(); if (["SUCCESS", "COMPLETED", "DONE", "INDEXED", "READY", "2"].includes(value)) return { tone: "success", label: "成功" }; if (["FAILED", "ERROR", "ROLLED_BACK", "-1"].includes(value)) return { tone: "danger", label: value === "ROLLED_BACK" ? "已回滚" : "失败" }; if (["RUNNING", "PROCESSING", "1"].includes(value)) return { tone: "running", label: "处理中" }; if (["PENDING", "WAITING", "0"].includes(value)) return { tone: "warning", label: "等待中" }; return { tone: "neutral", label: status ? String(status) : "未知" }; }
function errorMessage(error: unknown) { return error instanceof Error ? error.message : "请求失败，请稍后重试"; }
function showError(error: unknown) { toast.error(errorMessage(error)); }
