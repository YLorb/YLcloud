import * as DropdownMenu from "@radix-ui/react-dropdown-menu";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Bot, Check, ChevronRight, Copy, Database, Download, FolderInput, FolderPlus, Grid2X2, Link2, List, MoreHorizontal,
  Pencil, Plus, RefreshCw, RotateCcw, Search, Trash2, Upload, X
} from "lucide-react";
import { useDeferredValue, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { toast } from "sonner";
import { api } from "../../api";
import type { Category } from "../../appTypes";
import { Button } from "../../components/ui/Button";
import { Dialog } from "../../components/ui/Dialog";
import { EmptyState, ErrorState, LoadingState } from "../../components/ui/PageState";
import { categoryMeta, fileIcon, fileTypeLabel, formatSize, formatTime, matchesCategory } from "../../fileUtils";
import type { FileItem, FilePreview } from "../../types";
import { uploadFileWithResume, type UploadProgress } from "./multipartUpload";
import { FileExplorerBreadcrumbs, FileExplorerSearch } from "./FileExplorerChrome";

type Crumb = { id: number; name: string };

export function FilesPage() {
  const client = useQueryClient();
  const navigate = useNavigate();
  const fileInput = useRef<HTMLInputElement>(null);
  const [params, setParams] = useSearchParams();
  const parentId = Number(params.get("parent") || 0);
  const category = (params.get("category") || "all") as Category;
  const [query, setQuery] = useState("");
  const deferredQuery = useDeferredValue(query);
  const [view, setView] = useState<"list" | "grid">(() => localStorage.getItem("ylcloud_files_view") === "grid" ? "grid" : "list");
  const [crumbs, setCrumbs] = useState<Crumb[]>([{ id: 0, name: "我的文件" }]);
  const [folderOpen, setFolderOpen] = useState(false);
  const [folderName, setFolderName] = useState("");
  const [deleteTarget, setDeleteTarget] = useState<FileItem | null>(null);
  const [renameTarget, setRenameTarget] = useState<FileItem | null>(null);
  const [renameName, setRenameName] = useState("");
  const [knowledgeTarget, setKnowledgeTarget] = useState<FileItem | null>(null);
  const [batchKnowledgeOpen, setBatchKnowledgeOpen] = useState(false);
  const [knowledgeSpaceId, setKnowledgeSpaceId] = useState("");
  const [transfer, setTransfer] = useState<{ item: FileItem; mode: "move" | "copy" } | null>(null);
  const [batchTransfer, setBatchTransfer] = useState<"move" | "copy" | null>(null);
  const [batchDeleteOpen, setBatchDeleteOpen] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [transferFolderId, setTransferFolderId] = useState("0");
  const [preview, setPreview] = useState<FilePreview | null>(null);
  const [uploadProgress, setUploadProgress] = useState<UploadProgress | null>(null);

  const isRecycle = category === "recycle";
  const isAggregate = !["all", "recycle"].includes(category);
  const files = useQuery({
    queryKey: isRecycle ? ["recycle-files"] : isAggregate ? ["file-category", category, deferredQuery] : ["files", parentId],
    queryFn: () => isRecycle ? api.listRecycle() : isAggregate ? api.listFilesByCategory(category, deferredQuery.trim() || undefined) : api.listFiles(parentId)
  });
  const publicSettings = useQuery({ queryKey: ["public-settings"], queryFn: api.publicSettings, staleTime: 300_000 });
  const spaces = useQuery({ queryKey: ["spaces"], queryFn: api.listSpaces, enabled: Boolean(knowledgeTarget) || batchKnowledgeOpen });
  const rootFolders = useQuery({ queryKey: ["files", 0], queryFn: () => api.listFiles(0), enabled: Boolean(transfer || batchTransfer) });

  const visibleFiles = useMemo(() => (files.data || [])
    .filter((item) => matchesCategory(item, category))
    .filter((item) => isAggregate || item.name.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase())), [category, files.data, isAggregate, query]);

  const selectedItems = useMemo(() => visibleFiles.filter((item) => selectedIds.has(item.fileId)), [selectedIds, visibleFiles]);
  const selectedDocuments = useMemo(() => selectedItems.filter((item) => !item.isDir), [selectedItems]);
  const allSelected = visibleFiles.length > 0 && visibleFiles.every((item) => selectedIds.has(item.fileId));
  useEffect(() => {
    const visible = new Set(visibleFiles.map((item) => item.fileId));
    setSelectedIds((current) => new Set([...current].filter((id) => visible.has(id))));
  }, [category, parentId, files.data]);

  const refresh = () => client.invalidateQueries({ queryKey: isRecycle ? ["recycle-files"] : isAggregate ? ["file-category"] : ["files", parentId] });
  const createFolder = useMutation({
    mutationFn: () => api.createFile({ isDir: 1, parentId, name: folderName.trim() }),
    onSuccess: () => { setFolderOpen(false); setFolderName(""); refresh(); toast.success("文件夹创建成功"); },
    onError: (error) => toast.error(error instanceof Error ? error.message : "文件夹创建失败")
  });
  const deleteFile = useMutation({
    mutationFn: (item: FileItem) => api.deleteFile(item.fileUuid, item.parentId),
    onSuccess: () => { setDeleteTarget(null); refresh(); toast.success("文件已移入回收站"); },
    onError: (error) => toast.error(error instanceof Error ? error.message : "删除失败")
  });
  const restoreFile = useMutation({
    mutationFn: (item: FileItem) => api.restoreRecycle(item.fileId),
    onSuccess: () => { refresh(); toast.success("文件恢复成功"); },
    onError: (error) => toast.error(error instanceof Error ? error.message : "恢复失败")
  });
  const purgeFile = useMutation({
    mutationFn: (item: FileItem) => api.deleteRecycle(item.fileId),
    onSuccess: () => { setDeleteTarget(null); refresh(); toast.success("文件已永久删除"); },
    onError: (error) => toast.error(error instanceof Error ? error.message : "永久删除失败")
  });
  const renameFile = useMutation({
    mutationFn: () => api.renameFile(renameTarget!.fileUuid, renameTarget!.parentId, renameName.trim()),
    onSuccess: () => { setRenameTarget(null); refresh(); toast.success("重命名成功"); },
    onError: (error) => toast.error(error instanceof Error ? error.message : "重命名失败")
  });
  const addToKnowledge = useMutation({
    mutationFn: () => api.importSpaceFile(Number(knowledgeSpaceId), { userFileId: knowledgeTarget!.fileId, parentId: null, name: knowledgeTarget!.name }),
    onSuccess: () => { setKnowledgeTarget(null); toast.success("已添加到知识库，文档将开始 RAG 处理"); },
    onError: (error) => toast.error(error instanceof Error ? error.message : "添加到知识库失败")
  });
  const batchAddToKnowledge = useMutation({
    mutationFn: () => Promise.all(selectedDocuments.map((item) => api.importSpaceFile(Number(knowledgeSpaceId), { userFileId: item.fileId, parentId: null, name: item.name }))),
    onSuccess: () => {
      const count = selectedDocuments.length;
      setBatchKnowledgeOpen(false);
      setSelectedIds(new Set());
      toast.success(`已将 ${count} 个文档添加到知识库`);
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "批量添加到知识库失败")
  });
  const transferFile = useMutation({
    mutationFn: () => transfer!.mode === "move" ? api.batchMoveFiles([transfer!.item.fileId], Number(transferFolderId)) : api.batchCopyFiles([transfer!.item.fileId], Number(transferFolderId)),
    onSuccess: () => { const mode = transfer?.mode; setTransfer(null); refresh(); toast.success(mode === "move" ? "文件已移动" : "文件已复制"); },
    onError: (error) => toast.error(error instanceof Error ? error.message : "文件转移失败")
  });
  const batchDelete = useMutation({
    mutationFn: () => api.batchDeleteFiles(selectedItems.map((item) => item.fileId)),
    onSuccess: () => { setBatchDeleteOpen(false); setSelectedIds(new Set()); refresh(); toast.success(`已将 ${selectedItems.length} 项移入回收站`); },
    onError: (error) => toast.error(error instanceof Error ? error.message : "批量删除失败，未执行任何修改")
  });
  const batchTransferFiles = useMutation({
    mutationFn: () => batchTransfer === "move" ? api.batchMoveFiles(selectedItems.map((item) => item.fileId), Number(transferFolderId)) : api.batchCopyFiles(selectedItems.map((item) => item.fileId), Number(transferFolderId)),
    onSuccess: () => { const count = selectedItems.length; const mode = batchTransfer; setBatchTransfer(null); setSelectedIds(new Set()); refresh(); toast.success(`已${mode === "move" ? "移动" : "复制"} ${count} 项`); },
    onError: (error) => toast.error(error instanceof Error ? error.message : "批量操作失败，未执行任何修改")
  });

  function switchCategory(next: Category) {
    const nextParams = new URLSearchParams(params);
    nextParams.set("category", next);
    nextParams.delete("parent");
    setParams(nextParams);
    setSelectedIds(new Set());
    setCrumbs([{ id: 0, name: categoryMeta.find((item) => item.key === next)?.label || "我的文件" }]);
  }

  function openFolder(item: FileItem) {
    if (!item.isDir || isRecycle || isAggregate) return;
    const nextParams = new URLSearchParams(params);
    nextParams.set("parent", String(item.fileId));
    setParams(nextParams);
    setCrumbs((current) => [...current.filter((crumb) => crumb.id !== item.fileId), { id: item.fileId, name: item.name }]);
  }

  function openCrumb(crumb: Crumb) {
    const nextParams = new URLSearchParams(params);
    if (crumb.id === 0) nextParams.delete("parent"); else nextParams.set("parent", String(crumb.id));
    setParams(nextParams);
    setCrumbs((current) => current.slice(0, current.findIndex((item) => item.id === crumb.id) + 1));
  }

  async function upload(selected: FileList | null) {
    if (!selected?.length) return;
    const threshold = publicSettings.data?.multipartUploadThresholdBytes || 10 * 1024 * 1024;
    let successful = 0;
    for (const file of Array.from(selected)) {
      try {
        await uploadFileWithResume(file, parentId, threshold, { onProgress: setUploadProgress });
        successful += 1;
      } catch (error) {
        toast.error(`${file.name} 上传失败：${error instanceof Error ? error.message : "未知错误"}`);
      }
    }
    setUploadProgress(null);
    if (successful) toast.success(`成功上传 ${successful} 个文件`);
    refresh();
    if (fileInput.current) fileInput.current.value = "";
  }

  async function previewFile(item: FileItem) {
    if (item.isDir) return openFolder(item);
    try { setPreview(await api.previewFile(item.fileUuid, item.parentId)); }
    catch (error) { toast.error(error instanceof Error ? error.message : "预览失败"); }
  }

  function toggleSelection(item: FileItem) {
    setSelectedIds((current) => { const next = new Set(current); next.has(item.fileId) ? next.delete(item.fileId) : next.add(item.fileId); return next; });
  }

  function toggleAll() {
    setSelectedIds(allSelected ? new Set() : new Set(visibleFiles.map((item) => item.fileId)));
  }

  async function share(item: FileItem) {
    try {
      const code = await api.shareFile(item.fileUuid, item.parentId);
      const url = `${window.location.origin}/share/${code}`;
      await navigator.clipboard.writeText(url);
      toast.success("分享链接已复制");
    } catch (error) { toast.error(error instanceof Error ? error.message : "创建分享链接失败"); }
  }

  async function downloadSelected() {
    try {
      for (const item of selectedDocuments) {
        await api.downloadFile(item.fileUuid, item.parentId, item.name);
      }
      toast.success(`已开始下载 ${selectedDocuments.length} 个文件`);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "批量下载失败");
    }
  }

  return (
    <div className="files-page">
      <section className="feature-main">
        <div className="content-toolbar">
          <FileExplorerBreadcrumbs crumbs={crumbs} label="文件路径" onOpen={openCrumb} />
          <div className="toolbar-actions">
            {!isRecycle && !isAggregate && <><input ref={fileInput} hidden type="file" multiple onChange={(event) => upload(event.target.files)} /><Button variant="primary" onClick={() => fileInput.current?.click()}><Upload size={16} />上传文件</Button><Button onClick={() => setFolderOpen(true)}><FolderPlus size={16} />新建文件夹</Button></>}
            <Button variant="ghost" size="icon" aria-label="刷新" onClick={() => refresh()}><RefreshCw size={17} /></Button>
          </div>
        </div>
        <div className="filter-bar">
          <FileExplorerSearch value={query} onChange={setQuery} placeholder={isAggregate ? "搜索全部已存文件" : "搜索当前视图"} />
          <div className="segmented-control" aria-label="视图模式"><button className={view === "list" ? "active" : ""} onClick={() => { setView("list"); localStorage.setItem("ylcloud_files_view", "list"); }} aria-label="列表视图"><List size={17} /></button><button className={view === "grid" ? "active" : ""} onClick={() => { setView("grid"); localStorage.setItem("ylcloud_files_view", "grid"); }} aria-label="网格视图"><Grid2X2 size={17} /></button></div>
        </div>
        {selectedItems.length > 0 && !isRecycle && <div className="bulk-action-bar" role="region" aria-label="批量文件操作"><strong>已选择 {selectedItems.length} 项</strong><span>文件夹不会下载或添加到知识库。</span><div><Button disabled={!selectedDocuments.length} onClick={downloadSelected}><Download size={16} />下载</Button><Button onClick={() => { setBatchTransfer("move"); setTransferFolderId("0"); }}><FolderInput size={16} />移动</Button><Button onClick={() => { setBatchTransfer("copy"); setTransferFolderId("0"); }}><Copy size={16} />复制</Button><Button disabled={!selectedDocuments.length} onClick={() => { setKnowledgeSpaceId(""); setBatchKnowledgeOpen(true); }}><Database size={16} />添加到知识库</Button><Button variant="danger" onClick={() => setBatchDeleteOpen(true)}><Trash2 size={16} />删除</Button><Button variant="ghost" onClick={() => setSelectedIds(new Set())}>取消选择</Button></div></div>}
        {uploadProgress && <div className="upload-strip" role="status"><span><Upload size={16} />正在{uploadProgress.stage === "hashing" ? "校验" : uploadProgress.stage === "merging" ? "合并" : "上传"} {uploadProgress.fileName}</span><progress max={100} value={uploadProgress.percent} /><strong>{uploadProgress.percent}%</strong></div>}
        {files.isLoading ? <LoadingState label="正在加载文件" /> : files.isError ? <ErrorState message={files.error instanceof Error ? files.error.message : "无法加载文件"} onRetry={() => files.refetch()} /> : visibleFiles.length === 0 ? (!query && !isRecycle && !isAggregate && parentId === 0
          ? <FileOnboarding onUpload={() => fileInput.current?.click()} onAsk={() => navigate("/assistant")} />
          : <EmptyState title={query ? "没有匹配的文件" : isRecycle ? "回收站为空" : "这里还没有文件"} message={query ? "请尝试其他关键词。" : isRecycle ? "删除的文件会暂时保留在这里。" : "上传文件或创建文件夹开始整理资料。"} action={!isRecycle && !query ? <Button variant="primary" onClick={() => fileInput.current?.click()}><Plus size={16} />上传文件</Button> : undefined} />) : (
          <div className={view === "grid" ? "file-grid" : "data-table-wrap"}>
            {view === "list" ? <table className="data-table"><thead><tr><th className="selection-cell"><input type="checkbox" checked={allSelected} aria-checked={allSelected ? true : selectedItems.length ? "mixed" : false} onChange={toggleAll} aria-label="选择当前结果中的全部文件" /></th><th>名称</th><th>类型</th><th>大小</th><th>更新时间</th><th><span className="sr-only">操作</span></th></tr></thead><tbody>{visibleFiles.map((item) => <tr className={selectedIds.has(item.fileId) ? "is-selected" : ""} key={item.fileId} onDoubleClick={() => previewFile(item)}><td className="selection-cell"><input type="checkbox" checked={selectedIds.has(item.fileId)} onChange={() => toggleSelection(item)} aria-label={`选择 ${item.name}`} /></td><td><button className="file-name" onClick={() => previewFile(item)}>{fileIcon(item, 19)}<span>{item.name}</span></button></td><td>{fileTypeLabel(item)}</td><td>{item.isDir ? "—" : formatSize(item.size)}</td><td>{formatTime(item.updateTime || item.createTime)}</td><td><FileMenu item={item} recycle={isRecycle} onPreview={() => previewFile(item)} onDownload={() => api.downloadFile(item.fileUuid, item.parentId, item.name).catch((error) => toast.error(error.message))} onShare={() => share(item)} onRestore={() => restoreFile.mutate(item)} onRename={() => { setRenameTarget(item); setRenameName(item.name); }} onKnowledge={() => { setKnowledgeTarget(item); setKnowledgeSpaceId(""); }} onTransfer={(mode) => { setTransfer({ item, mode }); setTransferFolderId("0"); }} onDelete={() => setDeleteTarget(item)} /></td></tr>)}</tbody></table> : visibleFiles.map((item) => <article className={selectedIds.has(item.fileId) ? "file-card file-card--selected" : "file-card"} key={item.fileId} onDoubleClick={() => previewFile(item)}><label className="file-card__select"><input type="checkbox" checked={selectedIds.has(item.fileId)} onChange={() => toggleSelection(item)} /><span className="sr-only">选择 {item.name}</span></label><div className="file-card__icon">{fileIcon(item, 36)}</div><button className="file-card__name" onClick={() => previewFile(item)}>{item.name}</button><span>{item.isDir ? "文件夹" : formatSize(item.size)}</span><FileMenu item={item} recycle={isRecycle} onPreview={() => previewFile(item)} onDownload={() => api.downloadFile(item.fileUuid, item.parentId, item.name).catch((error) => toast.error(error.message))} onShare={() => share(item)} onRestore={() => restoreFile.mutate(item)} onRename={() => { setRenameTarget(item); setRenameName(item.name); }} onKnowledge={() => { setKnowledgeTarget(item); setKnowledgeSpaceId(""); }} onTransfer={(mode) => { setTransfer({ item, mode }); setTransferFolderId("0"); }} onDelete={() => setDeleteTarget(item)} /></article>)}
          </div>
        )}
      </section>
      <Dialog open={folderOpen} onOpenChange={setFolderOpen} title="新建文件夹" description="文件夹将创建在当前路径。" footer={<><Button onClick={() => setFolderOpen(false)}>取消</Button><Button variant="confirm" loading={createFolder.isPending} disabled={!folderName.trim()} onClick={() => createFolder.mutate()}>确认创建</Button></>}><label className="field"><span>文件夹名称</span><input autoFocus value={folderName} onChange={(event) => setFolderName(event.target.value)} maxLength={128} placeholder="例如：项目资料" /></label></Dialog>
      <Dialog open={Boolean(deleteTarget)} onOpenChange={(open) => !open && setDeleteTarget(null)} title={isRecycle ? "永久删除文件？" : "移入回收站？"} description={isRecycle ? "此操作不可撤销，文件数据将被永久删除。" : "文件可在回收站中恢复。"} footer={<><Button onClick={() => setDeleteTarget(null)}>取消</Button><Button variant="danger" loading={deleteFile.isPending || purgeFile.isPending} onClick={() => deleteTarget && (isRecycle ? purgeFile.mutate(deleteTarget) : deleteFile.mutate(deleteTarget))}><Trash2 size={16} />{isRecycle ? "永久删除" : "移入回收站"}</Button></>}><div className="danger-callout">将处理：<strong>{deleteTarget?.name}</strong></div></Dialog>
      <Dialog open={Boolean(preview)} onOpenChange={(open) => !open && setPreview(null)} title={preview?.name || "文件预览"} footer={<Button onClick={() => setPreview(null)}>关闭</Button>}><PreviewContent preview={preview} /></Dialog>
      <Dialog open={Boolean(renameTarget)} onOpenChange={(open) => !open && setRenameTarget(null)} title="重命名" footer={<><Button onClick={() => setRenameTarget(null)}>取消</Button><Button variant="confirm" loading={renameFile.isPending} disabled={!renameName.trim() || renameName.trim() === renameTarget?.name} onClick={() => renameFile.mutate()}><Pencil size={16} />确认修改</Button></>}><label className="field"><span>新名称</span><input autoFocus value={renameName} onChange={(event) => setRenameName(event.target.value)} /></label></Dialog>
      <Dialog open={Boolean(knowledgeTarget)} onOpenChange={(open) => !open && setKnowledgeTarget(null)} title="添加到知识库" description="文档将加入所选空间，并依次执行解析、切分、Embedding 和索引。" footer={<><Button onClick={() => setKnowledgeTarget(null)}>取消</Button><Button variant="confirm" loading={addToKnowledge.isPending} disabled={!knowledgeSpaceId} onClick={() => addToKnowledge.mutate()}><Database size={16} />确认添加</Button></>}><div className="form-stack"><div className="info-callout">目标文档：<strong>{knowledgeTarget?.name}</strong></div><label className="field"><span>目标知识库</span><select value={knowledgeSpaceId} onChange={(event) => setKnowledgeSpaceId(event.target.value)}><option value="">请选择团队空间</option>{(spaces.data || []).map((space) => <option key={space.id} value={space.id}>{space.name}</option>)}</select></label></div></Dialog>
      <Dialog open={batchKnowledgeOpen} onOpenChange={setBatchKnowledgeOpen} title="批量添加到知识库" description="所选文档将依次执行解析、切分、Embedding 和索引。" footer={<><Button onClick={() => setBatchKnowledgeOpen(false)}>取消</Button><Button variant="confirm" loading={batchAddToKnowledge.isPending} disabled={!knowledgeSpaceId || !selectedDocuments.length} onClick={() => batchAddToKnowledge.mutate()}><Database size={16} />确认添加</Button></>}><div className="form-stack"><div className="info-callout">将添加 <strong>{selectedDocuments.length}</strong> 个文档{selectedItems.length !== selectedDocuments.length && `，已忽略 ${selectedItems.length - selectedDocuments.length} 个文件夹`}。</div><label className="field"><span>目标知识库</span><select value={knowledgeSpaceId} onChange={(event) => setKnowledgeSpaceId(event.target.value)}><option value="">请选择团队空间</option>{(spaces.data || []).map((space) => <option key={space.id} value={space.id}>{space.name}</option>)}</select></label></div></Dialog>
      <Dialog open={Boolean(transfer)} onOpenChange={(open) => !open && setTransfer(null)} title={transfer?.mode === "move" ? "移动文件" : "复制文件"} description="选择根目录或一个根级文件夹作为目标位置。" footer={<><Button onClick={() => setTransfer(null)}>取消</Button><Button variant="confirm" loading={transferFile.isPending} onClick={() => transferFile.mutate()}>{transfer?.mode === "move" ? <FolderInput size={16} /> : <Copy size={16} />}确认{transfer?.mode === "move" ? "移动" : "复制"}</Button></>}><label className="field"><span>目标位置</span><select value={transferFolderId} onChange={(event) => setTransferFolderId(event.target.value)}><option value="0">我的文件（根目录）</option>{(rootFolders.data || []).filter((item) => item.isDir && item.fileId !== transfer?.item.fileId).map((folder) => <option key={folder.fileId} value={folder.fileId}>{folder.name}</option>)}</select></label></Dialog>
      <Dialog open={Boolean(batchTransfer)} onOpenChange={(open) => !open && setBatchTransfer(null)} title={batchTransfer === "move" ? "批量移动" : "批量复制"} description={`将一次性处理 ${selectedItems.length} 项；任一项失败时数据库整体回滚。`} footer={<><Button onClick={() => setBatchTransfer(null)}>取消</Button><Button variant="confirm" loading={batchTransferFiles.isPending} onClick={() => batchTransferFiles.mutate()}>{batchTransfer === "move" ? <FolderInput size={16} /> : <Copy size={16} />}确认{batchTransfer === "move" ? "移动" : "复制"}</Button></>}><label className="field"><span>目标位置</span><select value={transferFolderId} onChange={(event) => setTransferFolderId(event.target.value)}><option value="0">我的文件（根目录）</option>{(rootFolders.data || []).filter((item) => item.isDir && !selectedIds.has(item.fileId)).map((folder) => <option key={folder.fileId} value={folder.fileId}>{folder.name}</option>)}</select></label></Dialog>
      <Dialog open={batchDeleteOpen} onOpenChange={setBatchDeleteOpen} title="将所选文件移入回收站？" description="文件之后仍可从回收站恢复；本次批量操作失败时不会保留部分修改。" footer={<><Button onClick={() => setBatchDeleteOpen(false)}>取消</Button><Button variant="danger" loading={batchDelete.isPending} onClick={() => batchDelete.mutate()}><Trash2 size={16} />移入回收站</Button></>}><div className="danger-callout">将处理 <strong>{selectedItems.length}</strong> 个文件或文件夹。</div></Dialog>
    </div>
  );
}

function FileOnboarding({ onUpload, onAsk }: { onUpload: () => void; onAsk: () => void }) {
  return <section className="file-onboarding" aria-labelledby="file-onboarding-title">
    <div className="file-onboarding__intro">
      <span className="file-onboarding__icon"><Upload size={24} /></span>
      <div><span className="section-eyebrow">从这里开始</span><h2 id="file-onboarding-title">上传资料，然后直接提问</h2><p>PDF、Word 等资料上传后会进入解析和索引流程。处理完成后，回答可以追溯到原文。</p></div>
    </div>
    <ol className="file-onboarding__steps">
      <li className="is-current"><span>1</span><div><strong>上传文件</strong><small>选择要整理和检索的资料</small></div></li>
      <li><span>2</span><div><strong>系统处理</strong><small>自动解析、切分并建立索引</small></div></li>
      <li><span>3</span><div><strong>开始提问</strong><small>获取带原文引用的回答</small></div></li>
    </ol>
    <div className="file-onboarding__actions"><Button variant="primary" size="lg" onClick={onUpload}><Upload size={17} />上传第一个文件</Button><Button onClick={onAsk}><Bot size={17} />查看智能问答</Button></div>
    <p className="file-onboarding__note"><Check size={15} />上传不会改变原文件，处理进度可随时在“任务”中查看。</p>
  </section>;
}

function FileMenu({ item, recycle, onPreview, onDownload, onShare, onRestore, onRename, onKnowledge, onTransfer, onDelete }: { item: FileItem; recycle: boolean; onPreview: () => void; onDownload: () => void; onShare: () => void; onRestore: () => void; onRename: () => void; onKnowledge: () => void; onTransfer: (mode: "move" | "copy") => void; onDelete: () => void }) {
  return <DropdownMenu.Root><DropdownMenu.Trigger asChild><Button variant="ghost" size="icon" aria-label={`打开 ${item.name} 的操作菜单`}><MoreHorizontal size={17} /></Button></DropdownMenu.Trigger><DropdownMenu.Portal><DropdownMenu.Content className="dropdown-menu" align="end">{recycle ? <DropdownMenu.Item onSelect={onRestore}><RotateCcw size={15} />恢复</DropdownMenu.Item> : <><DropdownMenu.Item onSelect={onPreview}>{item.isDir ? <ChevronRight size={15} /> : <Search size={15} />}{item.isDir ? "打开" : "预览"}</DropdownMenu.Item>{!item.isDir && <><DropdownMenu.Item onSelect={onDownload}><Download size={15} />下载</DropdownMenu.Item><DropdownMenu.Item onSelect={onKnowledge}><Database size={15} />添加到知识库</DropdownMenu.Item></>}<DropdownMenu.Item onSelect={onShare}><Link2 size={15} />复制分享链接</DropdownMenu.Item><DropdownMenu.Item onSelect={onRename}><Pencil size={15} />重命名</DropdownMenu.Item><DropdownMenu.Item onSelect={() => onTransfer("move")}><FolderInput size={15} />移动</DropdownMenu.Item><DropdownMenu.Item onSelect={() => onTransfer("copy")}><Copy size={15} />复制</DropdownMenu.Item></>}<DropdownMenu.Separator /><DropdownMenu.Item className="dropdown-danger" onSelect={onDelete}><Trash2 size={15} />{recycle ? "永久删除" : "移入回收站"}</DropdownMenu.Item></DropdownMenu.Content></DropdownMenu.Portal></DropdownMenu.Root>;
}

function PreviewContent({ preview }: { preview: FilePreview | null }) {
  if (!preview) return null;
  if (preview.textContent) return <pre className="text-preview">{preview.textContent}</pre>;
  if (preview.previewUrl && preview.contentType?.startsWith("image/")) return <img className="media-preview" src={preview.previewUrl} alt={preview.name} />;
  if (preview.previewUrl) return <iframe className="document-preview" src={preview.previewUrl} title={preview.name} />;
  return <EmptyState title="暂不支持在线预览" message="你仍可下载该文件后查看。" />;
}
