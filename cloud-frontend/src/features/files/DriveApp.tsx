import { ChangeEvent, useEffect, useMemo, useRef, useState } from "react";
import { ArchiveRestore, Bot, ChevronLeft, ChevronRight, Clock3, Download, Eye, Folder, FolderPlus, HardDrive, Loader2, LogOut, MoreHorizontal, Network, RefreshCw, Search, Settings, UploadCloud, UserRound, X } from "lucide-react";
import { api, clearSession } from "../../api";
import type { Category, Crumb, MainView, Notice } from "../../appTypes";
import type { FileItem, FilePreview, PublicSiteSettings, Space, User } from "../../types";
import { NoticeBar } from "../../components/NoticeBar";
import { categoryMeta, extOf, fileIcon, fileTypeLabel, formatSize, formatTime, imageTypes, matchesCategory } from "../../fileUtils";
import { AsyncTasksView } from "../async/AsyncTasksView";
import { ChatView } from "../chat/ChatView";
import { SettingsPanel } from "../settings/SettingsPanel";
import { SpacesView } from "../spaces/SpacesView";

function EmptyState({ category }: { category: Category }) {
  const title = category === "recycle" ? "回收站是空的" : "这里还没有文件";
  const desc =
    category === "recycle"
      ? "删除后的文件会显示在这里，你可以恢复或彻底删除。"
      : "上传文件或新建文件夹后，它们会出现在当前目录中。";
  return (
    <div className="empty-state">
      <div className="empty-icon">
        <Folder size={34} />
      </div>
      <h3>{title}</h3>
      <p>{desc}</p>
    </div>
  );
}

function withStop(event: React.MouseEvent, action: () => void) {
  event.stopPropagation();
  action();
}

function FileTable({
  files,
  selected,
  selectedIds,
  category,
  onSelect,
  onToggleSelect,
  onToggleAll,
  onOpen,
  onPreview,
  onDownload,
  onRename,
  onDelete,
  onRestore,
  onDeleteForever,
  onAddToKnowledge,
  onContextMenu
}: {
  files: FileItem[];
  selected: FileItem | null;
  selectedIds: Set<number>;
  category: Category;
  onSelect: (item: FileItem) => void;
  onToggleSelect: (item: FileItem) => void;
  onToggleAll: () => void;
  onOpen: (item: FileItem) => void;
  onPreview: (item: FileItem) => void;
  onDownload: (item: FileItem) => void;
  onRename: (item: FileItem) => void;
  onDelete: (item: FileItem) => void;
  onRestore: (item: FileItem) => void;
  onDeleteForever: (item: FileItem) => void;
  onAddToKnowledge: (items: FileItem[]) => void;
  onContextMenu: (item: FileItem, x: number, y: number) => void;
}) {
  if (!files.length) return <EmptyState category={category} />;

  const selectableFiles = files.filter((item) => !item.isDir && category !== "recycle");
  const allSelected = selectableFiles.length > 0 && selectableFiles.every((item) => selectedIds.has(item.fileId));

  return (
    <div className="table-card">
      <div className="file-row table-head">
        <span className="select-cell">
          {category !== "recycle" && (
            <input
              aria-label="选择当前列表文件"
              checked={allSelected}
              disabled={!selectableFiles.length}
              type="checkbox"
              onChange={onToggleAll}
            />
          )}
        </span>
        <span>名称</span>
        <span>大小</span>
        <span>类型</span>
        <span>修改时间</span>
        <span>操作</span>
      </div>
      {files.map((item) => (
        <div
          className={`file-row ${selected?.fileId === item.fileId ? "selected" : ""}`}
          key={`${item.fileId}-${item.fileUuid}`}
          onClick={() => onSelect(item)}
          onDoubleClick={() => onOpen(item)}
          onContextMenu={(event) => {
            if (category === "recycle" || item.isDir) return;
            event.preventDefault();
            onContextMenu(item, event.clientX, event.clientY);
          }}
          role="button"
          tabIndex={0}
          onKeyDown={(event) => {
            if (event.key === "Enter") onOpen(item);
          }}
        >
          <span className="select-cell">
            {category !== "recycle" && !item.isDir && (
              <input
                aria-label={`选择 ${item.name}`}
                checked={selectedIds.has(item.fileId)}
                type="checkbox"
                onChange={() => onToggleSelect(item)}
                onClick={(event) => event.stopPropagation()}
              />
            )}
          </span>
          <span className="name-cell">
            <span className={`file-mark ${item.isDir ? "folder" : ""}`}>{fileIcon(item)}</span>
            <span className="name-text">{item.name}</span>
          </span>
          <span>{item.isDir ? "-" : formatSize(item.size)}</span>
          <span>{fileTypeLabel(item)}</span>
          <span>{formatTime(item.updateTime || item.createTime)}</span>
          <span className="row-actions">
            {category === "recycle" ? (
              <>
                <button type="button" onClick={(event) => withStop(event, () => onRestore(item))}>
                  恢复
                </button>
                <button className="danger" type="button" onClick={(event) => withStop(event, () => onDeleteForever(item))}>
                  删除
                </button>
              </>
            ) : (
              <>
                <button type="button" onClick={(event) => withStop(event, () => onOpen(item))}>
                  {item.isDir ? "进入" : "打开"}
                </button>
                {!item.isDir && (
                  <>
                    <button type="button" onClick={(event) => withStop(event, () => onPreview(item))}>
                      预览
                    </button>
                    <button type="button" onClick={(event) => withStop(event, () => onDownload(item))}>
                      下载
                    </button>
                    <button type="button" onClick={(event) => withStop(event, () => onAddToKnowledge([item]))}>
                      添加到知识库
                    </button>
                  </>
                )}
                <button type="button" onClick={(event) => withStop(event, () => onRename(item))}>
                  重命名
                </button>
                <button className="danger" type="button" onClick={(event) => withStop(event, () => onDelete(item))}>
                  删除
                </button>
              </>
            )}
          </span>
        </div>
      ))}
    </div>
  );
}

function PreviewModal({ preview, file, onClose }: { preview: FilePreview | null; file: FileItem | null; onClose: () => void }) {
  if (!file) return null;
  const previewUrl = preview?.previewUrl;
  const isImage = preview?.previewType === "image" || imageTypes.has(extOf(file));
  const isText = !!preview?.textContent;

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <section className="preview-modal" onClick={(event) => event.stopPropagation()}>
        <header>
          <div>
            <h3>{file.name}</h3>
            <p>{preview?.contentType || fileTypeLabel(file)}</p>
          </div>
          <button type="button" onClick={onClose} aria-label="关闭预览">
            <X size={18} />
          </button>
        </header>
        <div className="preview-body">
          {isText ? (
            <pre>{preview?.textContent}</pre>
          ) : isImage && previewUrl ? (
            <img src={previewUrl} alt={file.name} />
          ) : previewUrl ? (
            <iframe title={file.name} src={previewUrl} />
          ) : (
            <div className="preview-placeholder">
              <Eye size={34} />
              <h4>预览占位</h4>
              <p>后端已提供 `/api/file/preview/{`{fileUuid}`}` 接口。若需要内嵌流式预览，可继续补充可直接访问的预览 URL。</p>
            </div>
          )}
        </div>
      </section>
    </div>
  );
}

function KnowledgeTargetModal({
  files,
  onClose,
  onImported
}: {
  files: FileItem[];
  onClose: () => void;
  onImported: (message: string) => void;
}) {
  const [spaces, setSpaces] = useState<Space[]>([]);
  const [spaceId, setSpaceId] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const importableFiles = files.filter((item) => !item.isDir);

  useEffect(() => {
    setLoading(true);
    api.listSpaces()
      .then((items) => {
        const next = items || [];
        setSpaces(next);
        setSpaceId(next[0]?.id ? String(next[0].id) : "");
      })
      .catch((err) => setError(err instanceof Error ? err.message : "知识库列表加载失败"))
      .finally(() => setLoading(false));
  }, []);

  async function submit() {
    if (!spaceId || !importableFiles.length) return;
    setLoading(true);
    setError("");
    try {
      const targetId = Number(spaceId);
      for (const file of importableFiles) {
        await api.importSpaceFile(targetId, { userFileId: file.fileId, parentId: null, name: file.name });
      }
      onImported(`已添加 ${importableFiles.length} 个文件到知识库`);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "添加到知识库失败");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <section className="dialog-modal compact-dialog" onClick={(event) => event.stopPropagation()}>
        <header>
          <div>
            <h3>添加到知识库</h3>
            <p>选择一个有权限的团队空间，文件会导入对应知识库并进入索引流程。</p>
          </div>
          <button type="button" onClick={onClose} aria-label="关闭">
            <X size={18} />
          </button>
        </header>
        <div className="dialog-body">
          <label>
            目标知识库
            <select value={spaceId} onChange={(event) => setSpaceId(event.target.value)} disabled={loading || !spaces.length}>
              {spaces.map((space) => (
                <option key={space.id} value={space.id}>
                  {space.name}（{space.role || "MEMBER"}）
                </option>
              ))}
            </select>
          </label>
          <div className="selected-file-list">
            {importableFiles.map((file) => (
              <span key={file.fileId}>{file.name}</span>
            ))}
            {!importableFiles.length && <span>当前选择中没有可导入的文件</span>}
          </div>
          {error && <div className="form-error">{error}</div>}
        </div>
        <footer className="dialog-actions">
          <button className="soft-button" type="button" onClick={onClose}>
            取消
          </button>
          <button className="primary-button" type="button" disabled={loading || !spaceId || !importableFiles.length} onClick={() => void submit()}>
            {loading && <Loader2 className="spin" size={16} />}
            添加 {importableFiles.length} 个文件
          </button>
        </footer>
      </section>
    </div>
  );
}

export function DriveApp({
  user,
  publicSettings,
  path,
  onNavigate,
  onLogout
}: {
  user: User;
  publicSettings: PublicSiteSettings | null;
  path: string;
  onNavigate: (path: string) => void;
  onLogout: () => void;
}) {
  const [mainView, setMainView] = useState<MainView>(path === "/settings" ? "settings" : path === "/chat" ? "chat" : path === "/async" ? "async" : "files");
  const [category, setCategory] = useState<Category>("all");
  const [files, setFiles] = useState<FileItem[]>([]);
  const [selected, setSelected] = useState<FileItem | null>(null);
  const [crumbs, setCrumbs] = useState<Crumb[]>([{ id: 0, name: "我的文件" }]);
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(false);
  const [notice, setNotice] = useState<Notice>(null);
  const [error, setError] = useState("");
  const [previewFile, setPreviewFile] = useState<FileItem | null>(null);
  const [preview, setPreview] = useState<FilePreview | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [knowledgeFiles, setKnowledgeFiles] = useState<FileItem[]>([]);
  const [contextMenu, setContextMenu] = useState<{ item: FileItem; x: number; y: number } | null>(null);
  const uploadInput = useRef<HTMLInputElement>(null);
  const parentId = crumbs[crumbs.length - 1]?.id ?? 0;
  const isAdmin = user.role?.toUpperCase() === "ADMIN";
  const isSettingsPage = isAdmin && path === "/settings";
  const isChatPage = path === "/chat";
  const isAsyncPage = path === "/async";
  const siteName = publicSettings?.siteName || "YL Cloud";
  const effectiveView: MainView = isSettingsPage ? "settings" : isChatPage ? "chat" : isAsyncPage ? "async" : mainView === "settings" ? "files" : mainView;

  useEffect(() => {
    if (isSettingsPage) {
      setMainView("settings");
    } else if (isChatPage) {
      setMainView("chat");
    } else if (isAsyncPage) {
      setMainView("async");
    } else if (mainView === "settings") {
      setMainView("files");
    }
  }, [isSettingsPage, isChatPage, isAsyncPage, mainView]);

  const filteredFiles = useMemo(() => {
    const normalizedQuery = query.trim().toLowerCase();
    return files.filter((item) => {
      const categoryMatch = matchesCategory(item, category);
      const queryMatch = !normalizedQuery || item.name.toLowerCase().includes(normalizedQuery);
      return categoryMatch && queryMatch;
    });
  }, [files, category, query]);

  const totalSize = useMemo(() => files.reduce((sum, item) => sum + (item.size || 0), 0), [files]);

  async function loadFiles(nextParentId = parentId, nextCategory = category) {
    setLoading(true);
    setError("");
    setSelected(null);
    setSelectedIds(new Set());
    try {
      const data = nextCategory === "recycle" ? await api.listRecycle() : await api.listFiles(nextParentId);
      setFiles(data || []);
    } catch (err) {
      const message = err instanceof Error ? err.message : "文件列表加载失败";
      setError(message);
      setFiles([]);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadFiles(0, "all");
  }, []);

  function showNotice(next: Notice) {
    setNotice(next);
    if (next) window.setTimeout(() => setNotice(null), 3200);
  }

  async function switchCategory(next: Category) {
    onNavigate("/");
    setMainView("files");
    setCategory(next);
    setQuery("");
    setPreviewFile(null);
    setPreview(null);
    setSelectedIds(new Set());
    if (next !== "recycle") setCrumbs([{ id: 0, name: "我的文件" }]);
    await loadFiles(0, next);
  }

  async function upload(event: ChangeEvent<HTMLInputElement>) {
    const selectedFiles = event.target.files;
    if (!selectedFiles?.length) return;
    setLoading(true);
    setError("");
    try {
      for (const file of Array.from(selectedFiles)) {
        await api.uploadFile(file, parentId);
      }
      await loadFiles(parentId, category);
      showNotice({ type: "success", text: "文件上传完成" });
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "上传失败" });
    } finally {
      setLoading(false);
      event.target.value = "";
    }
  }

  async function createFolder() {
    const name = window.prompt("请输入文件夹名称");
    if (!name?.trim()) return;
    try {
      await api.createFile({ isDir: 1, parentId, name: name.trim(), type: "folder" });
      await loadFiles(parentId, category);
      showNotice({ type: "success", text: "文件夹已创建" });
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "创建文件夹失败" });
    }
  }

  async function renameFile(item: FileItem) {
    const newName = window.prompt("请输入新的名称", item.name);
    if (!newName?.trim() || newName === item.name) return;
    try {
      await api.renameFile(item.fileUuid, item.parentId ?? parentId, newName.trim());
      await loadFiles(parentId, category);
      showNotice({ type: "success", text: "重命名成功" });
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "重命名失败" });
    }
  }

  async function deleteFile(item: FileItem) {
    if (!window.confirm(`确认删除「${item.name}」？文件会进入回收站。`)) return;
    try {
      await api.deleteFile(item.fileUuid, item.parentId ?? parentId);
      await loadFiles(parentId, category);
      showNotice({ type: "success", text: "已移入回收站" });
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "删除失败" });
    }
  }

  async function restoreFile(item: FileItem) {
    try {
      await api.restoreRecycle(item.fileId);
      await loadFiles(0, "recycle");
      showNotice({ type: "success", text: "文件已恢复" });
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "恢复失败" });
    }
  }

  async function deleteForever(item: FileItem) {
    if (!window.confirm(`确认彻底删除「${item.name}」？此操作不可恢复。`)) return;
    try {
      await api.deleteRecycle(item.fileId);
      await loadFiles(0, "recycle");
      showNotice({ type: "success", text: "已彻底删除" });
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "彻底删除失败" });
    }
  }

  async function downloadFile(item: FileItem) {
    try {
      await api.downloadFile(item.fileUuid, item.parentId ?? parentId, item.name);
      showNotice({ type: "info", text: "已开始下载" });
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "下载失败" });
    }
  }

  async function previewItem(item: FileItem) {
    setPreviewFile(item);
    setPreview(null);
    try {
      setPreview(await api.previewFile(item.fileUuid, item.parentId ?? parentId));
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "预览失败，已保留占位窗口" });
    }
  }

  async function openItem(item: FileItem) {
    setSelected(item);
    if (item.isDir) {
      const nextCrumbs = [...crumbs, { id: item.fileId, name: item.name }];
      setCrumbs(nextCrumbs);
      await loadFiles(item.fileId, category);
      return;
    }
    await previewItem(item);
  }

  async function jumpTo(index: number) {
    const next = crumbs.slice(0, index + 1);
    setCrumbs(next);
    await loadFiles(next[next.length - 1].id, category);
  }

  function goUp() {
    if (crumbs.length <= 1) return;
    void jumpTo(crumbs.length - 2);
  }

  function toggleSelectedFile(item: FileItem) {
    if (item.isDir) return;
    setSelectedIds((current) => {
      const next = new Set(current);
      if (next.has(item.fileId)) next.delete(item.fileId);
      else next.add(item.fileId);
      return next;
    });
  }

  function toggleAllFiles() {
    const selectable = filteredFiles.filter((item) => !item.isDir && category !== "recycle");
    setSelectedIds((current) => {
      const allSelected = selectable.length > 0 && selectable.every((item) => current.has(item.fileId));
      return allSelected ? new Set() : new Set(selectable.map((item) => item.fileId));
    });
  }

  function openAddToKnowledge(items: FileItem[]) {
    const importable = items.filter((item) => !item.isDir);
    if (!importable.length) {
      showNotice({ type: "info", text: "当前选择中没有可添加到知识库的文件" });
      return;
    }
    setContextMenu(null);
    setKnowledgeFiles(importable);
  }

  function logout() {
    clearSession();
    onLogout();
  }

  const batchFiles = filteredFiles.filter((item) => selectedIds.has(item.fileId) && !item.isDir);

  const topbarTitle =
    effectiveView === "settings"
      ? "系统设置"
      : effectiveView === "chat"
        ? "智能问答"
      : effectiveView === "async"
        ? "异步任务"
      : effectiveView === "spaces"
        ? "团队空间"
        : categoryMeta.find((item) => item.key === category)?.label || "全部文件";
  const topbarDescription =
    effectiveView === "settings"
      ? "管理站点信息、访问网址和模型服务配置。"
      : effectiveView === "chat"
        ? "像 ChatGPT 一样提问，可选择知识库作为检索范围。"
      : effectiveView === "async"
        ? "独立查看后台任务执行进度、阶段和结果。"
      : effectiveView === "spaces"
        ? "成员协作、版本管理和 RAG 问答。"
        : "现代化文件管理，适配当前 YLCloud 后端接口。";

  return (
    <main className="drive-shell">
      <aside className="sidebar">
        <div className="brand compact">
          <span className="brand-icon">
            <HardDrive size={22} />
          </span>
          <span>{siteName}</span>
        </div>

        <nav className="side-nav" aria-label="功能导航">
          {categoryMeta.map((item) => (
            <button
              className={effectiveView === "files" && category === item.key ? "active" : ""}
              key={item.key}
              type="button"
              onClick={() => void switchCategory(item.key)}
            >
              {item.icon}
              {item.label}
            </button>
          ))}
          <button
            className={`side-section-start ${effectiveView === "spaces" ? "active" : ""}`}
            type="button"
            onClick={() => {
              onNavigate("/");
              setMainView("spaces");
            }}
          >
            <Network size={18} />
            团队空间
          </button>
          <button
            className={effectiveView === "chat" ? "active" : ""}
            type="button"
            onClick={() => {
              setMainView("chat");
              onNavigate("/chat");
            }}
          >
            <Bot size={18} />
            智能问答
          </button>
          <button
            className={effectiveView === "async" ? "active" : ""}
            type="button"
            onClick={() => {
              setMainView("async");
              onNavigate("/async");
            }}
          >
            <Clock3 size={18} />
            异步任务
          </button>
          {isAdmin && (
            <button
              className={`side-section-start ${effectiveView === "settings" ? "active" : ""}`}
              type="button"
              onClick={() => {
                setMainView("settings");
                onNavigate("/settings");
              }}
            >
              <Settings size={18} />
              系统设置
            </button>
          )}
        </nav>

        <div className="storage-card">
          <div>
            <strong>{formatSize(totalSize)}</strong>
            <span>当前目录容量</span>
          </div>
          <div className="storage-bar">
            <span style={{ width: `${Math.min(92, Math.max(8, totalSize / 1024 / 1024))}%` }} />
          </div>
        </div>
      </aside>

      <section className="content">
        <header className="topbar">
          <div>
            <h1>{topbarTitle}</h1>
            <p>{topbarDescription}</p>
          </div>
          <div className="topbar-right">
            <label className="search-box">
              <Search size={18} />
              <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索文件或文件夹" />
            </label>
            <div className="user-chip">
              <UserRound size={18} />
              <span>{user.nickname || user.username}</span>
            </div>
            <button className="icon-button" type="button" onClick={logout} title="退出登录" aria-label="退出登录">
              <LogOut size={18} />
            </button>
          </div>
        </header>

        {effectiveView === "settings" ? (
          <SettingsPanel onNotice={showNotice} />
        ) : effectiveView === "chat" ? (
          <ChatView showNotice={showNotice} />
        ) : effectiveView === "async" ? (
          <AsyncTasksView showNotice={showNotice} />
        ) : effectiveView === "spaces" ? (
          <SpacesView showNotice={showNotice} />
        ) : (
          <>
            <section className="file-panel">
              <div className="panel-toolbar">
                <div>
                  <div className="breadcrumbs">
                    {category === "recycle" ? (
                      <span>回收站</span>
                    ) : (
                      crumbs.map((crumb, index) => (
                        <button key={`${crumb.id}-${index}`} type="button" onClick={() => void jumpTo(index)}>
                          {crumb.name}
                          {index < crumbs.length - 1 && <ChevronRight size={14} />}
                        </button>
                      ))
                    )}
                  </div>
                  <p>
                    {filteredFiles.length} 项
                    {query && `，匹配「${query}」`}
                  </p>
                </div>

                <div className="toolbar-actions">
                  {category !== "recycle" && (
                    <button className="soft-button" type="button" disabled={crumbs.length <= 1} onClick={goUp}>
                      <ChevronLeft size={17} />
                      返回上级
                    </button>
                  )}
                  <button className="icon-button" type="button" onClick={() => void loadFiles(parentId, category)} title="刷新">
                    <RefreshCw size={18} />
                  </button>
                  {category !== "recycle" && (
                    <>
                      <input ref={uploadInput} type="file" multiple hidden onChange={upload} />
                      <button className="primary-button" type="button" onClick={() => uploadInput.current?.click()}>
                        <UploadCloud size={17} />
                        上传
                      </button>
                      <button className="soft-button" type="button" onClick={() => void createFolder()}>
                        <FolderPlus size={17} />
                        新建文件夹
                      </button>
                    </>
                  )}
                </div>
              </div>

              {category !== "recycle" && selectedIds.size > 0 && (
                <div className="selection-toolbar">
                  <span>已选择 {batchFiles.length} 个文件</span>
                  <div>
                    <button className="soft-button" type="button" onClick={() => setSelectedIds(new Set())}>
                      取消选择
                    </button>
                    <button className="primary-button" type="button" disabled={!batchFiles.length} onClick={() => openAddToKnowledge(batchFiles)}>
                      添加到知识库
                    </button>
                  </div>
                </div>
              )}

              {error && (
                <div className="error-state">
                  <strong>加载失败</strong>
                  <span>{error}</span>
                  <button type="button" onClick={() => void loadFiles(parentId, category)}>
                    重试
                  </button>
                </div>
              )}

              {loading ? (
                <div className="loading-state">
                  <Loader2 className="spin" size={24} />
                  正在同步文件列表
                </div>
              ) : (
                !error && (
                  <FileTable
                    files={filteredFiles}
                    selected={selected}
                    selectedIds={selectedIds}
                    category={category}
                    onSelect={setSelected}
                    onToggleSelect={toggleSelectedFile}
                    onToggleAll={toggleAllFiles}
                    onOpen={(item) => void openItem(item)}
                    onPreview={(item) => void previewItem(item)}
                    onDownload={(item) => void downloadFile(item)}
                    onRename={(item) => void renameFile(item)}
                    onDelete={(item) => void deleteFile(item)}
                    onRestore={(item) => void restoreFile(item)}
                    onDeleteForever={(item) => void deleteForever(item)}
                    onAddToKnowledge={(items) => openAddToKnowledge(items)}
                    onContextMenu={(item, x, y) => setContextMenu({ item, x, y })}
                  />
                )
              )}
            </section>

            <section className="detail-strip">
              {selected ? (
                <>
                  <span className={`file-mark large ${selected.isDir ? "folder" : ""}`}>{fileIcon(selected, 28)}</span>
                  <div>
                    <h3>{selected.name}</h3>
                    <p>
                      {fileTypeLabel(selected)} · {selected.isDir ? "文件夹" : formatSize(selected.size)} ·{" "}
                      {formatTime(selected.updateTime || selected.createTime)}
                    </p>
                  </div>
                  <div className="detail-actions">
                    {!selected.isDir && (
                      <>
                        <button type="button" onClick={() => void previewItem(selected)}>
                          <Eye size={16} />
                          预览
                        </button>
                        <button type="button" onClick={() => void downloadFile(selected)}>
                          <Download size={16} />
                          下载
                        </button>
                        {category !== "recycle" && (
                          <button type="button" onClick={() => openAddToKnowledge([selected])}>
                            <Bot size={16} />
                            添加到知识库
                          </button>
                        )}
                      </>
                    )}
                    {category === "recycle" ? (
                      <button type="button" onClick={() => void restoreFile(selected)}>
                        <ArchiveRestore size={16} />
                        恢复
                      </button>
                    ) : (
                      <button type="button" onClick={() => void renameFile(selected)}>
                        <MoreHorizontal size={16} />
                        重命名
                      </button>
                    )}
                  </div>
                </>
              ) : (
                <p>选择一个文件或文件夹后，可在这里查看详情和快捷操作。</p>
              )}
            </section>
          </>
        )}
      </section>

      {contextMenu && (
        <div className="context-menu" style={{ left: contextMenu.x, top: contextMenu.y }} role="menu">
          <button type="button" onClick={() => openAddToKnowledge([contextMenu.item])}>
            添加到知识库
          </button>
          <button type="button" onClick={() => setContextMenu(null)}>
            关闭
          </button>
        </div>
      )}
      {!!knowledgeFiles.length && (
        <KnowledgeTargetModal
          files={knowledgeFiles}
          onClose={() => setKnowledgeFiles([])}
          onImported={(message) => {
            showNotice({ type: "success", text: message });
            setSelectedIds(new Set());
          }}
        />
      )}
      <NoticeBar notice={notice} onClose={() => setNotice(null)} />
      <PreviewModal preview={preview} file={previewFile} onClose={() => setPreviewFile(null)} />
    </main>
  );
}
