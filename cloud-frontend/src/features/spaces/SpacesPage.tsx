import * as Select from "@radix-ui/react-select";
import * as Switch from "@radix-ui/react-switch";
import * as Tabs from "@radix-ui/react-tabs";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, ChevronDown, Download, FileInput, FileUp, Folder, FolderPlus, History, Link2, Plus, RefreshCw, RotateCcw, Search, Trash2, UserPlus, Users } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { toast } from "sonner";
import { api } from "../../api";
import { Button } from "../../components/ui/Button";
import { Dialog } from "../../components/ui/Dialog";
import { EmptyState, ErrorState, LoadingState } from "../../components/ui/PageState";
import { StatusBadge, type StatusTone } from "../../components/ui/StatusBadge";
import { formatSize, formatTime } from "../../fileUtils";
import type { FileVersion, SpaceFile } from "../../types";

export function SpacesPage() {
  const client = useQueryClient();
  const uploadRef = useRef<HTMLInputElement>(null);
  const versionUploadRef = useRef<HTMLInputElement>(null);
  const [params, setParams] = useSearchParams();
  const spaces = useQuery({ queryKey: ["spaces"], queryFn: api.listSpaces });
  const requestedId = Number(params.get("space") || 0);
  const activeSpaceId = requestedId || spaces.data?.[0]?.id || 0;
  const tab = params.get("tab") || "files";
  const [query, setQuery] = useState("");
  const [spaceDialog, setSpaceDialog] = useState(false);
  const [folderDialog, setFolderDialog] = useState(false);
  const [spaceName, setSpaceName] = useState("");
  const [spaceDescription, setSpaceDescription] = useState("");
  const [folderName, setFolderName] = useState("");
  const [deleteTarget, setDeleteTarget] = useState<SpaceFile | null>(null);
  const [importDialog, setImportDialog] = useState(false);
  const [personalFileId, setPersonalFileId] = useState("");
  const [linkDialog, setLinkDialog] = useState(false);
  const [linkUrl, setLinkUrl] = useState("");
  const [linkName, setLinkName] = useState("");
  const [memberDialog, setMemberDialog] = useState(false);
  const [memberUserId, setMemberUserId] = useState("");
  const [memberRole, setMemberRole] = useState("MEMBER");
  const [versionTarget, setVersionTarget] = useState<SpaceFile | null>(null);

  useEffect(() => {
    if (!requestedId && spaces.data?.[0]) setParams((current) => { const next = new URLSearchParams(current); next.set("space", String(spaces.data![0].id)); return next; }, { replace: true });
  }, [requestedId, setParams, spaces.data]);

  const files = useQuery({ queryKey: ["space-files", activeSpaceId], queryFn: () => api.listSpaceFiles(activeSpaceId), enabled: activeSpaceId > 0 });
  const members = useQuery({ queryKey: ["space-members", activeSpaceId], queryFn: () => api.listMembers(activeSpaceId), enabled: activeSpaceId > 0 && tab === "members" });
  const personalFiles = useQuery({ queryKey: ["files", 0], queryFn: () => api.listFiles(0), enabled: importDialog });
  const versions = useQuery({ queryKey: ["space-file-versions", activeSpaceId, versionTarget?.id], queryFn: () => api.listSpaceFileVersions(activeSpaceId, versionTarget!.id), enabled: Boolean(versionTarget) });
  const activeSpace = spaces.data?.find((space) => space.id === activeSpaceId);
  const visibleFiles = useMemo(() => (files.data || []).filter((file) => file.name.toLocaleLowerCase().includes(query.toLocaleLowerCase())), [files.data, query]);

  const createSpace = useMutation({ mutationFn: () => api.createSpace({ name: spaceName.trim(), description: spaceDescription.trim() }), onSuccess: async (space) => { await client.invalidateQueries({ queryKey: ["spaces"] }); setParams({ space: String(space.id), tab: "files" }); setSpaceDialog(false); setSpaceName(""); setSpaceDescription(""); toast.success("团队空间创建成功"); }, onError: showError });
  const createFolder = useMutation({ mutationFn: () => api.createSpaceFolder(activeSpaceId, { name: folderName.trim() }), onSuccess: () => { client.invalidateQueries({ queryKey: ["space-files", activeSpaceId] }); setFolderDialog(false); setFolderName(""); toast.success("文件夹创建成功"); }, onError: showError });
  const removeFile = useMutation({ mutationFn: (file: SpaceFile) => api.removeSpaceFile(activeSpaceId, file.id), onSuccess: () => { client.invalidateQueries({ queryKey: ["space-files", activeSpaceId] }); setDeleteTarget(null); toast.success("文件已从空间移除"); }, onError: showError });
  const importFile = useMutation({ mutationFn: () => api.importSpaceFile(activeSpaceId, { userFileId: Number(personalFileId), parentId: null }), onSuccess: () => { client.invalidateQueries({ queryKey: ["space-files", activeSpaceId] }); setImportDialog(false); setPersonalFileId(""); toast.success("个人文件已导入空间"); }, onError: showError });
  const importLink = useMutation({ mutationFn: () => api.importSpaceWebLink(activeSpaceId, { url: linkUrl.trim(), name: linkName.trim() || undefined }), onSuccess: () => { client.invalidateQueries({ queryKey: ["space-files", activeSpaceId] }); setLinkDialog(false); setLinkUrl(""); setLinkName(""); toast.success("网页链接已导入，正在进入 RAG 处理流程"); }, onError: showError });
  const addMember = useMutation({ mutationFn: () => api.addMember(activeSpaceId, { userId: Number(memberUserId), role: memberRole }), onSuccess: () => { client.invalidateQueries({ queryKey: ["space-members", activeSpaceId] }); setMemberDialog(false); setMemberUserId(""); toast.success("成员添加成功"); }, onError: showError });
  const updateMember = useMutation({ mutationFn: ({ userId, role }: { userId: number; role: string }) => api.updateMemberRole(activeSpaceId, userId, role), onSuccess: () => { client.invalidateQueries({ queryKey: ["space-members", activeSpaceId] }); toast.success("成员角色已更新"); }, onError: showError });
  const removeMember = useMutation({ mutationFn: (userId: number) => api.removeMember(activeSpaceId, userId), onSuccess: () => { client.invalidateQueries({ queryKey: ["space-members", activeSpaceId] }); toast.success("成员已移除"); }, onError: showError });
  const toggleVersions = useMutation({ mutationFn: (enabled: boolean) => api.updateSpaceVersionSetting(activeSpaceId, enabled ? 1 : 0), onSuccess: () => { client.invalidateQueries({ queryKey: ["spaces"] }); client.invalidateQueries({ queryKey: ["space-files", activeSpaceId] }); toast.success("空间版本设置已更新"); }, onError: showError });
  const restoreVersion = useMutation({ mutationFn: (version: FileVersion) => api.restoreSpaceFileVersion(activeSpaceId, versionTarget!.id, version.id), onSuccess: () => { client.invalidateQueries({ queryKey: ["space-file-versions", activeSpaceId, versionTarget?.id] }); toast.success("文件版本已恢复"); }, onError: showError });

  function chooseSpace(value: string) {
    setParams((current) => { const next = new URLSearchParams(current); next.set("space", value); return next; });
  }
  function chooseTab(value: string) {
    setParams((current) => { const next = new URLSearchParams(current); next.set("tab", value); return next; });
  }
  async function upload(filesToUpload: FileList | null) {
    if (!filesToUpload?.length || !activeSpaceId) return;
    let success = 0;
    for (const file of Array.from(filesToUpload)) {
      try { await api.uploadSpaceFile(activeSpaceId, file); success += 1; }
      catch (error) { showError(error); }
    }
    if (uploadRef.current) uploadRef.current.value = "";
    await client.invalidateQueries({ queryKey: ["space-files", activeSpaceId] });
    if (success) toast.success(`成功上传 ${success} 个文件`);
  }
  async function uploadVersion(file: File | undefined) {
    if (!file || !versionTarget) return;
    try {
      await api.uploadSpaceFileVersion(activeSpaceId, versionTarget.id, file, "通过新版前端上传");
      await client.invalidateQueries({ queryKey: ["space-file-versions", activeSpaceId, versionTarget.id] });
      toast.success("新版本上传成功");
    } catch (error) { showError(error); }
    if (versionUploadRef.current) versionUploadRef.current.value = "";
  }

  if (spaces.isLoading) return <LoadingState label="正在加载团队空间" />;
  if (spaces.isError) return <ErrorState message={spaces.error instanceof Error ? spaces.error.message : "无法加载团队空间"} onRetry={() => spaces.refetch()} />;

  return (
    <div className="spaces-page">
      <section className="space-switcher-card">
        <div><span className="section-eyebrow">当前空间</span><Select.Root value={activeSpaceId ? String(activeSpaceId) : undefined} onValueChange={chooseSpace}><Select.Trigger className="select-trigger" aria-label="选择团队空间"><Select.Value placeholder="请选择空间" /><Select.Icon><ChevronDown size={16} /></Select.Icon></Select.Trigger><Select.Portal><Select.Content className="select-content" position="popper"><Select.Viewport>{spaces.data?.map((space) => <Select.Item className="select-item" value={String(space.id)} key={space.id}><Select.ItemText>{space.name}</Select.ItemText><Select.ItemIndicator><Check size={14} /></Select.ItemIndicator></Select.Item>)}</Select.Viewport></Select.Content></Select.Portal></Select.Root><p>{activeSpace?.description || "集中管理团队共享资料和知识资产。"}</p></div>
        <Button variant="confirm" onClick={() => setSpaceDialog(true)}><Plus size={16} />创建空间</Button>
      </section>
      {!activeSpaceId ? <EmptyState title="还没有团队空间" message="创建空间后即可上传文档、邀请成员并构建知识库。" action={<Button variant="confirm" onClick={() => setSpaceDialog(true)}><Plus size={16} />创建第一个空间</Button>} /> : (
        <Tabs.Root value={tab} onValueChange={chooseTab} className="tabs-root">
          <Tabs.List className="tabs-list" aria-label="空间内容"><Tabs.Trigger value="files">文件</Tabs.Trigger><Tabs.Trigger value="members">成员</Tabs.Trigger><Tabs.Trigger value="settings">空间设置</Tabs.Trigger></Tabs.List>
          <Tabs.Content value="files" className="tab-panel">
            <div className="content-toolbar"><label className="search-box"><Search size={17} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索空间文件" /></label><div className="toolbar-actions"><input ref={uploadRef} hidden type="file" multiple onChange={(event) => upload(event.target.files)} /><Button variant="confirm" onClick={() => uploadRef.current?.click()}><FileUp size={16} />上传文档</Button><Button onClick={() => setImportDialog(true)}><FileInput size={16} />导入个人文件</Button><Button onClick={() => setLinkDialog(true)}><Link2 size={16} />导入网页</Button><Button onClick={() => setFolderDialog(true)}><FolderPlus size={16} />新建文件夹</Button><Button variant="ghost" size="icon" aria-label="刷新" onClick={() => files.refetch()}><RefreshCw size={17} /></Button></div></div>
            {files.isLoading ? <LoadingState label="正在加载空间文件" /> : files.isError ? <ErrorState message={files.error instanceof Error ? files.error.message : "无法加载空间文件"} onRetry={() => files.refetch()} /> : visibleFiles.length === 0 ? <EmptyState title={query ? "没有匹配的文件" : "空间中还没有文件"} message={query ? "请尝试其他关键词。" : "上传文档后，系统会按配置自动进入 RAG 处理流程。"} /> : <div className="data-table-wrap"><table className="data-table"><thead><tr><th>名称</th><th>类型</th><th>大小</th><th>知识状态</th><th>版本</th><th>更新时间</th><th><span className="sr-only">操作</span></th></tr></thead><tbody>{visibleFiles.map((file) => { const knowledge = knowledgeStatus(file); return <tr key={file.id}><td><span className="file-name">{file.dir ? <Folder size={18} /> : <FileUp size={18} />}<span>{file.name}{file.lastKnowledgeError && <small className="inline-error">{file.lastKnowledgeError}</small>}</span></span></td><td>{file.dir ? "文件夹" : (file.type || "文档").toUpperCase()}</td><td>{file.dir ? "—" : formatSize(file.size)}</td><td><StatusBadge tone={knowledge.tone}>{knowledge.label}</StatusBadge></td><td>{file.effectiveVersionEnabled ? <StatusBadge tone="success">已开启</StatusBadge> : <StatusBadge tone="neutral">未开启</StatusBadge>}</td><td>{formatTime(file.updatetime || file.createtime)}</td><td><div className="row-actions">{!file.dir && <><Button variant="ghost" size="icon" aria-label={`查看 ${file.name} 的版本`} onClick={() => setVersionTarget(file)}><History size={16} /></Button><Button variant="ghost" size="icon" aria-label={`下载 ${file.name}`} onClick={() => api.downloadSpaceFile(activeSpaceId, file.id, file.name).catch(showError)}><Download size={16} /></Button></>}<Button variant="ghost" size="icon" aria-label={`移除 ${file.name}`} onClick={() => setDeleteTarget(file)}><Trash2 size={16} /></Button></div></td></tr>; })}</tbody></table></div>}
          </Tabs.Content>
          <Tabs.Content value="members" className="tab-panel"><div className="content-toolbar"><div><span className="section-eyebrow">协作成员</span><strong>管理成员角色和访问权限</strong></div><Button variant="confirm" onClick={() => setMemberDialog(true)}><UserPlus size={16} />添加成员</Button></div>{members.isLoading ? <LoadingState label="正在加载成员" /> : members.isError ? <ErrorState message={members.error instanceof Error ? members.error.message : "无法加载成员"} onRetry={() => members.refetch()} /> : <div className="data-table-wrap"><table className="data-table"><thead><tr><th>成员</th><th>角色</th><th>状态</th><th>加入时间</th><th><span className="sr-only">操作</span></th></tr></thead><tbody>{(members.data || []).map((member) => <tr key={member.id}><td><span className="member-cell"><span><Users size={16} /></span>用户 #{member.userId}</span></td><td><select className="table-select" value={member.role || "MEMBER"} onChange={(event) => updateMember.mutate({ userId: member.userId, role: event.target.value })}><option value="OWNER">所有者</option><option value="ADMIN">管理员</option><option value="MEMBER">成员</option><option value="VIEWER">只读</option></select></td><td>{member.status === 0 ? <StatusBadge tone="danger">已停用</StatusBadge> : <StatusBadge tone="success">正常</StatusBadge>}</td><td>{formatTime(member.createtime)}</td><td><Button variant="ghost" size="icon" aria-label={`移除用户 ${member.userId}`} onClick={() => removeMember.mutate(member.userId)}><Trash2 size={16} /></Button></td></tr>)}</tbody></table></div>}</Tabs.Content>
          <Tabs.Content value="settings" className="tab-panel"><section className="settings-section"><div><span className="section-eyebrow">版本管理</span><h2>空间文件版本</h2><p>为上传到该空间的文件保留历史版本，便于审计与恢复。</p></div><label className="switch-field"><Switch.Root className="switch-root" checked={Boolean(activeSpace?.versionEnabled)} onCheckedChange={(checked) => toggleVersions.mutate(checked)}><Switch.Thumb className="switch-thumb" /></Switch.Root><span>{activeSpace?.versionEnabled ? "已开启" : "已关闭"}</span></label></section></Tabs.Content>
        </Tabs.Root>
      )}
      <Dialog open={spaceDialog} onOpenChange={setSpaceDialog} title="创建团队空间" description="空间用于组织共享文档、成员和知识库。" footer={<><Button onClick={() => setSpaceDialog(false)}>取消</Button><Button variant="confirm" loading={createSpace.isPending} disabled={!spaceName.trim()} onClick={() => createSpace.mutate()}>确认创建</Button></>}><div className="form-stack"><label className="field"><span>空间名称</span><input autoFocus value={spaceName} onChange={(event) => setSpaceName(event.target.value)} maxLength={64} /></label><label className="field"><span>描述（可选）</span><textarea value={spaceDescription} onChange={(event) => setSpaceDescription(event.target.value)} rows={3} /></label></div></Dialog>
      <Dialog open={folderDialog} onOpenChange={setFolderDialog} title="新建空间文件夹" footer={<><Button onClick={() => setFolderDialog(false)}>取消</Button><Button variant="confirm" loading={createFolder.isPending} disabled={!folderName.trim()} onClick={() => createFolder.mutate()}>确认创建</Button></>}><label className="field"><span>文件夹名称</span><input autoFocus value={folderName} onChange={(event) => setFolderName(event.target.value)} /></label></Dialog>
      <Dialog open={Boolean(deleteTarget)} onOpenChange={(open) => !open && setDeleteTarget(null)} title="从空间移除？" description="这会移除空间引用及关联处理数据，请确认没有其他成员正在使用。" footer={<><Button onClick={() => setDeleteTarget(null)}>取消</Button><Button variant="danger" loading={removeFile.isPending} onClick={() => deleteTarget && removeFile.mutate(deleteTarget)}><Trash2 size={16} />确认移除</Button></>}><div className="danger-callout">将移除：<strong>{deleteTarget?.name}</strong></div></Dialog>
      <Dialog open={importDialog} onOpenChange={setImportDialog} title="导入个人文件" description="选择个人文件后将其加入当前空间。" footer={<><Button onClick={() => setImportDialog(false)}>取消</Button><Button variant="confirm" loading={importFile.isPending} disabled={!personalFileId} onClick={() => importFile.mutate()}><FileInput size={16} />确认导入</Button></>}><label className="field"><span>个人文件</span><select value={personalFileId} onChange={(event) => setPersonalFileId(event.target.value)}><option value="">请选择文件</option>{(personalFiles.data || []).filter((file) => !file.isDir).map((file) => <option key={file.fileId} value={file.fileId}>{file.name}</option>)}</select></label></Dialog>
      <Dialog open={linkDialog} onOpenChange={setLinkDialog} title="导入网页链接" description="系统会抓取网页正文并作为知识库文档处理。" footer={<><Button onClick={() => setLinkDialog(false)}>取消</Button><Button variant="confirm" loading={importLink.isPending} disabled={!/^https?:\/\//i.test(linkUrl)} onClick={() => importLink.mutate()}><Link2 size={16} />确认导入</Button></>}><div className="form-stack"><label className="field"><span>网页地址</span><input type="url" value={linkUrl} onChange={(event) => setLinkUrl(event.target.value)} placeholder="https://example.com/article" /></label><label className="field"><span>文档名称（可选）</span><input value={linkName} onChange={(event) => setLinkName(event.target.value)} /></label></div></Dialog>
      <Dialog open={memberDialog} onOpenChange={setMemberDialog} title="添加空间成员" footer={<><Button onClick={() => setMemberDialog(false)}>取消</Button><Button variant="confirm" loading={addMember.isPending} disabled={!Number(memberUserId)} onClick={() => addMember.mutate()}><UserPlus size={16} />确认添加</Button></>}><div className="form-grid"><label className="field"><span>用户 ID</span><input type="number" min={1} value={memberUserId} onChange={(event) => setMemberUserId(event.target.value)} /></label><label className="field"><span>角色</span><select value={memberRole} onChange={(event) => setMemberRole(event.target.value)}><option value="MEMBER">成员</option><option value="ADMIN">管理员</option><option value="VIEWER">只读</option></select></label></div></Dialog>
      <Dialog open={Boolean(versionTarget)} onOpenChange={(open) => !open && setVersionTarget(null)} title={`${versionTarget?.name || "文件"} · 版本历史`} footer={<><input ref={versionUploadRef} hidden type="file" onChange={(event) => uploadVersion(event.target.files?.[0])} /><Button variant="confirm" onClick={() => versionUploadRef.current?.click()}><FileUp size={16} />上传新版本</Button><Button onClick={() => setVersionTarget(null)}>关闭</Button></>}><VersionHistory versions={versions.data || []} loading={versions.isLoading} onDownload={(version) => api.downloadSpaceFileVersion(activeSpaceId, versionTarget!.id, version.id, version.fileName || versionTarget!.name).catch(showError)} onRestore={(version) => restoreVersion.mutate(version)} /></Dialog>
    </div>
  );
}

function showError(error: unknown) { toast.error(error instanceof Error ? error.message : "操作失败，请稍后重试"); }

function knowledgeStatus(file: SpaceFile): { label: string; tone: StatusTone } {
  if (file.dir || file.knowledgeState === "NOT_APPLICABLE") return { label: "不适用", tone: "neutral" };
  if (file.knowledgeState === "READY" && file.searchable) return { label: "可检索", tone: "success" };
  if (file.knowledgeState === "FAILED") return { label: "处理失败", tone: "danger" };
  if (file.knowledgeState === "REMOVAL_PENDING") return { label: "正在移除", tone: "warning" };
  if (file.knowledgeState === "REMOVED") return { label: "已移除", tone: "neutral" };
  if (file.knowledgeState === "INDEXING") return { label: "正在处理", tone: "running" };
  return { label: "等待处理", tone: "info" };
}

function VersionHistory({ versions, loading, onDownload, onRestore }: { versions: FileVersion[]; loading: boolean; onDownload: (version: FileVersion) => void; onRestore: (version: FileVersion) => void }) {
  if (loading) return <LoadingState label="正在加载版本历史" />;
  if (!versions.length) return <EmptyState title="暂无历史版本" message="上传新版本后会在这里保留记录。" />;
  return <div className="version-list">{versions.map((version) => <article key={version.id}><span className="version-number">v{version.versionNo || version.id}</span><div><strong>{version.fileName || "文件版本"}</strong><small>{version.changeNote || "未填写变更说明"} · {formatTime(version.createtime)}</small></div>{version.current ? <StatusBadge tone="success">当前版本</StatusBadge> : <div className="row-actions"><Button variant="ghost" size="icon" aria-label="下载此版本" onClick={() => onDownload(version)}><Download size={15} /></Button><Button size="sm" onClick={() => onRestore(version)}><RotateCcw size={14} />恢复</Button></div>}</article>)}</div>;
}
