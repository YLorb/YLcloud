import {
  ArchiveRestore,
  Bot,
  ChevronLeft,
  ChevronRight,
  Download,
  Eye,
  File,
  FileArchive,
  FileAudio,
  FileImage,
  FileText,
  FileVideo,
  Folder,
  FolderPlus,
  HardDrive,
  Image,
  Loader2,
  LogOut,
  MoreHorizontal,
  Network,
  RefreshCw,
  Search,
  Settings,
  Settings2,
  Trash2,
  UploadCloud,
  UserRound,
  UsersRound,
  X
} from "lucide-react";
import { ChangeEvent, FormEvent, ReactNode, useEffect, useMemo, useRef, useState } from "react";
import { api, clearSession, getStoredUser, setSession } from "./api";
import type {
  FileItem,
  FilePreview,
  PublicSiteSettings,
  RagConfig,
  RagDocument,
  RagQuery,
  RagTask,
  SiteSetting,
  Space,
  SpaceFile,
  SpaceMember,
  User
} from "./types";

type AuthMode = "login" | "sign";
type MainView = "files" | "spaces" | "settings" | "chat";
type Category = "all" | "images" | "documents" | "videos" | "recycle";
type Notice = { type: "success" | "error" | "info"; text: string } | null;
type Crumb = { id: number; name: string };
type ChatMessage = { id: string; role: "user" | "assistant"; content: string };

const categoryMeta: Array<{ key: Category; label: string; icon: ReactNode }> = [
  { key: "all", label: "全部文件", icon: <HardDrive size={18} /> },
  { key: "images", label: "图片", icon: <Image size={18} /> },
  { key: "documents", label: "文档", icon: <FileText size={18} /> },
  { key: "videos", label: "视频", icon: <FileVideo size={18} /> },
  { key: "recycle", label: "回收站", icon: <Trash2 size={18} /> }
];

const imageTypes = new Set(["jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic"]);
const documentTypes = new Set(["doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf", "txt", "md", "csv", "json"]);
const videoTypes = new Set(["mp4", "mov", "mkv", "webm", "avi", "flv", "m4v"]);

function extOf(item: FileItem) {
  if (item.isDir) return "folder";
  return (item.type || item.name.split(".").pop() || "file").toLowerCase();
}

function matchesCategory(item: FileItem, category: Category) {
  if (category === "all" || category === "recycle") return true;
  if (item.isDir) return false;
  const ext = extOf(item);
  if (category === "images") return imageTypes.has(ext);
  if (category === "documents") return documentTypes.has(ext);
  if (category === "videos") return videoTypes.has(ext);
  return true;
}

function formatSize(size?: number) {
  if (!size) return "-";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = size;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  return `${value.toFixed(index === 0 || value >= 10 ? 0 : 1)} ${units[index]}`;
}

function formatTime(value?: string) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  });
}

function fileTypeLabel(item: FileItem) {
  if (item.isDir) return "文件夹";
  const ext = extOf(item);
  if (imageTypes.has(ext)) return "图片";
  if (documentTypes.has(ext)) return "文档";
  if (videoTypes.has(ext)) return "视频";
  return ext.toUpperCase();
}

function fileIcon(item: FileItem, size = 20) {
  if (item.isDir) return <Folder size={size} />;
  const ext = extOf(item);
  if (imageTypes.has(ext)) return <FileImage size={size} />;
  if (videoTypes.has(ext)) return <FileVideo size={size} />;
  if (documentTypes.has(ext)) return <FileText size={size} />;
  if (["zip", "rar", "7z", "tar", "gz"].includes(ext)) return <FileArchive size={size} />;
  if (["mp3", "wav", "flac", "aac"].includes(ext)) return <FileAudio size={size} />;
  return <File size={size} />;
}

function NoticeBar({ notice, onClose }: { notice: Notice; onClose: () => void }) {
  if (!notice) return null;
  return (
    <div className={`notice ${notice.type}`} role="status">
      <span>{notice.text}</span>
      <button type="button" aria-label="关闭提示" onClick={onClose}>
        <X size={14} />
      </button>
    </div>
  );
}

function AuthPage({
  mode,
  publicSettings,
  onNavigate,
  onSignedIn
}: {
  mode: AuthMode;
  publicSettings: PublicSiteSettings | null;
  onNavigate: (path: string) => void;
  onSignedIn: (user: User) => void;
}) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const isSign = mode === "sign";
  const siteName = publicSettings?.siteName || "YL Cloud";
  const allowRegister = publicSettings?.allowRegister ?? true;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    if (isSign && !allowRegister) {
      setError("当前站点未开放注册");
      return;
    }
    setLoading(true);
    const form = new FormData(event.currentTarget);
    const username = String(form.get("username") || "").trim();
    const password = String(form.get("password") || "");
    const nickname = String(form.get("nickname") || username).trim();

    try {
      if (isSign) {
        await api.sign({ username, password, nickname });
      }
      const user = await api.login(username, password);
      setSession(user);
      onSignedIn(user);
    } catch (err) {
      setError(err instanceof Error ? err.message : "认证失败，请稍后重试");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="auth-page">
      <section className="auth-card">
        <div className="brand">
          <span className="brand-icon">
            <HardDrive size={24} />
          </span>
          <span>{siteName}</span>
        </div>

        <div className="auth-card-title">
          <h1>{isSign ? "创建账号" : "登录到网盘"}</h1>
          <p>{isSign ? "注册后将自动登录并进入文件管理页面。" : `使用账号密码进入你的 ${siteName}。`}</p>
        </div>

        <form onSubmit={handleSubmit}>
          {isSign && (
            <label>
              昵称
              <input name="nickname" autoComplete="nickname" placeholder="例如：云盘管理员" required />
            </label>
          )}
          <label>
            用户名
            <input name="username" autoComplete="username" placeholder="请输入用户名" required />
          </label>
          <label>
            密码
            <input
              name="password"
              type="password"
              autoComplete={mode === "login" ? "current-password" : "new-password"}
              placeholder="请输入密码"
              required
            />
          </label>
          {error && <div className="form-error">{error}</div>}
          <button className="primary-button full" type="submit" disabled={loading || (isSign && !allowRegister)}>
            {loading && <Loader2 className="spin" size={16} />}
            {isSign && !allowRegister ? "注册已关闭" : isSign ? "注册并登录" : "进入网盘"}
          </button>
        </form>

        <p className="auth-switch">
          {isSign ? "已有账号？" : "还没有账号？"}
          <button type="button" onClick={() => onNavigate(isSign ? "/login" : "/sign")} disabled={!isSign && !allowRegister}>
            {isSign ? "返回登录" : "立即注册"}
          </button>
        </p>
      </section>
    </main>
  );
}

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

function DriveFilePickerModal({
  space,
  onClose,
  onImported
}: {
  space: Space;
  onClose: () => void;
  onImported: (count: number) => void;
}) {
  const [crumbs, setCrumbs] = useState<Crumb[]>([{ id: 0, name: "我的文件" }]);
  const [files, setFiles] = useState<FileItem[]>([]);
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const parentId = crumbs[crumbs.length - 1]?.id ?? 0;
  const selectedFiles = files.filter((file) => selectedIds.has(file.fileId) && !file.isDir);

  async function load(nextParentId = parentId) {
    setLoading(true);
    setError("");
    try {
      setFiles(await api.listFiles(nextParentId));
      setSelectedIds(new Set());
    } catch (err) {
      setError(err instanceof Error ? err.message : "网盘文件加载失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load(0);
  }, []);

  async function openFolder(file: FileItem) {
    if (!file.isDir) return;
    const next = [...crumbs, { id: file.fileId, name: file.name }];
    setCrumbs(next);
    await load(file.fileId);
  }

  async function jumpTo(index: number) {
    const next = crumbs.slice(0, index + 1);
    setCrumbs(next);
    await load(next[next.length - 1].id);
  }

  async function submit() {
    if (!selectedFiles.length) return;
    setLoading(true);
    setError("");
    try {
      for (const file of selectedFiles) {
        await api.importSpaceFile(space.id, { userFileId: file.fileId, parentId: null, name: file.name });
      }
      onImported(selectedFiles.length);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "导入文件失败");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <section className="dialog-modal picker-dialog" onClick={(event) => event.stopPropagation()}>
        <header>
          <div>
            <h3>从网盘选择文件</h3>
            <p>添加到「{space.name}」知识库</p>
          </div>
          <button type="button" onClick={onClose} aria-label="关闭">
            <X size={18} />
          </button>
        </header>
        <div className="picker-tabs">
          <button className="active" type="button">
            我的文件
          </button>
          <button type="button" disabled>
            项目文档
          </button>
        </div>
        <div className="picker-crumbs">
          {crumbs.map((crumb, index) => (
            <button key={`${crumb.id}-${index}`} type="button" onClick={() => void jumpTo(index)}>
              {crumb.name}
              {index < crumbs.length - 1 && <ChevronRight size={14} />}
            </button>
          ))}
        </div>
        <div className="picker-body">
          {error && <div className="form-error">{error}</div>}
          {loading ? (
            <div className="loading-state compact-loading">
              <Loader2 className="spin" size={20} />
              正在加载文件
            </div>
          ) : (
            <div className="picker-list">
              {files.map((file) => (
                <div className="picker-row" key={file.fileId}>
                  <input
                    aria-label={`选择 ${file.name}`}
                    checked={selectedIds.has(file.fileId)}
                    disabled={file.isDir}
                    type="checkbox"
                    onChange={() => {
                      setSelectedIds((current) => {
                        const next = new Set(current);
                        if (next.has(file.fileId)) next.delete(file.fileId);
                        else next.add(file.fileId);
                        return next;
                      });
                    }}
                  />
                  <button className="picker-file" type="button" onClick={() => void openFolder(file)}>
                    <span className={`file-mark ${file.isDir ? "folder" : ""}`}>{fileIcon(file, 18)}</span>
                    <span>
                      <strong>{file.name}</strong>
                      <small>{file.isDir ? "文件夹，可双击进入" : `${fileTypeLabel(file)} · ${formatSize(file.size)}`}</small>
                    </span>
                  </button>
                </div>
              ))}
              {!files.length && <p className="muted-line">当前目录暂无文件</p>}
            </div>
          )}
        </div>
        <footer className="dialog-actions">
          <button className="soft-button" type="button" onClick={onClose}>
            取消
          </button>
          <button className="primary-button" type="button" disabled={loading || !selectedFiles.length} onClick={() => void submit()}>
            添加 {selectedFiles.length} 个文件
          </button>
        </footer>
      </section>
    </div>
  );
}

function AddDocumentModal({
  space,
  onClose,
  onImported,
  onNotice
}: {
  space: Space;
  onClose: () => void;
  onImported: (count: number) => void;
  onNotice: (notice: Notice) => void;
}) {
  const [pickerOpen, setPickerOpen] = useState(false);
  const [uploading, setUploading] = useState(false);
  const localUploadInput = useRef<HTMLInputElement>(null);
  const folderUploadInput = useRef<HTMLInputElement>(null);

  async function uploadLocalFiles(event: ChangeEvent<HTMLInputElement>) {
    const selectedFiles = Array.from(event.target.files || []);
    if (!selectedFiles.length) return;
    setUploading(true);
    try {
      for (const file of selectedFiles) {
        await api.uploadSpaceFile(space.id, file, null);
      }
      onNotice({ type: "success", text: `已上传 ${selectedFiles.length} 个文件到知识库` });
      onImported(selectedFiles.length);
      onClose();
    } catch (err) {
      onNotice({ type: "error", text: err instanceof Error ? err.message : "本地上传失败" });
    } finally {
      setUploading(false);
      event.target.value = "";
    }
  }

  async function uploadFolder(event: ChangeEvent<HTMLInputElement>) {
    const selectedFiles = Array.from(event.target.files || []);
    if (!selectedFiles.length) return;
    const confirmed = window.confirm("正在上传文件夹内的文件，系统将递归读取文件夹内的所有文件并上传。是否继续？");
    if (!confirmed) {
      event.target.value = "";
      return;
    }
    setUploading(true);
    try {
      const folderCache = new Map<string, number | null>([["", null]]);
      async function ensureSpaceFolder(name: string, parentId: number | null, pathKey: string) {
        if (folderCache.has(pathKey)) return folderCache.get(pathKey) ?? null;
        const siblings = await api.listSpaceFiles(space.id, parentId);
        const existing = siblings.find((item) => item.dir && item.name === name);
        if (existing) {
          folderCache.set(pathKey, existing.id);
          return existing.id;
        }
        const created = await api.createSpaceFolder(space.id, { name, parentId });
        folderCache.set(pathKey, created.id);
        return created.id;
      }
      for (const file of selectedFiles) {
        const relativePath = (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name;
        const segments = relativePath.split("/").filter(Boolean);
        const fileName = segments.pop() || file.name;
        let parentId: number | null = null;
        let pathKey = "";
        for (const segment of segments) {
          pathKey = pathKey ? `${pathKey}/${segment}` : segment;
          parentId = await ensureSpaceFolder(segment, parentId, pathKey);
        }
        await api.uploadSpaceFile(space.id, file, parentId, fileName);
      }
      onNotice({ type: "success", text: `已上传文件夹内 ${selectedFiles.length} 个文件` });
      onImported(selectedFiles.length);
      onClose();
    } catch (err) {
      onNotice({ type: "error", text: err instanceof Error ? err.message : "文件夹上传失败" });
    } finally {
      setUploading(false);
      event.target.value = "";
    }
  }

  async function importLink() {
    const url = window.prompt("请输入网页链接");
    if (!url?.trim()) return;
    const name = window.prompt("可选：请输入保存的文档名称", "");
    setUploading(true);
    try {
      await api.importSpaceWebLink(space.id, { url: url.trim(), parentId: null, name: name?.trim() || undefined });
      onNotice({ type: "success", text: "网页链接已导入知识库" });
      onImported(1);
      onClose();
    } catch (err) {
      onNotice({ type: "error", text: err instanceof Error ? err.message : "网页链接导入失败" });
    } finally {
      setUploading(false);
    }
  }

  return (
    <>
      <div className="modal-backdrop" onClick={onClose}>
        <section className="dialog-modal compact-dialog" onClick={(event) => event.stopPropagation()}>
          <header>
            <div>
              <h3>添加文档</h3>
              <p>向「{space.name}」添加可索引文档。</p>
            </div>
            <button type="button" onClick={onClose} aria-label="关闭">
              <X size={18} />
            </button>
          </header>
          <div className="add-document-grid">
            <input ref={localUploadInput} type="file" multiple hidden onChange={(event) => void uploadLocalFiles(event)} />
            <input
              ref={folderUploadInput}
              type="file"
              multiple
              hidden
              onChange={(event) => void uploadFolder(event)}
              {...({ webkitdirectory: "", directory: "" } as Record<string, string>)}
            />
            <button type="button" onClick={() => setPickerOpen(true)}>
              <span className="file-mark">
                <HardDrive size={18} />
              </span>
              <strong>从网盘选择</strong>
              <small>选择我的文件并导入当前知识库</small>
            </button>
            <button type="button" disabled={uploading} onClick={() => localUploadInput.current?.click()}>
              <span className="file-mark">
                <UploadCloud size={18} />
              </span>
              <strong>本地上传</strong>
              <small>上传文件并直接加入当前 Space</small>
            </button>
            <button type="button" disabled={uploading} onClick={() => folderUploadInput.current?.click()}>
              <span className="file-mark folder">
                <FolderPlus size={18} />
              </span>
              <strong>上传文件夹</strong>
              <small>确认后递归读取文件夹内文件</small>
            </button>
            <button type="button" disabled={uploading} onClick={() => void importLink()}>
              <span className="file-mark">
                <Network size={18} />
              </span>
              <strong>输入网页链接</strong>
              <small>抓取网页内容并生成索引文档</small>
            </button>
          </div>
          <footer className="dialog-actions">
            <button className="soft-button" type="button" onClick={onClose}>
              关闭
            </button>
          </footer>
        </section>
      </div>
      {pickerOpen && (
        <DriveFilePickerModal
          space={space}
          onClose={() => setPickerOpen(false)}
          onImported={(count) => {
            onImported(count);
            setPickerOpen(false);
            onClose();
          }}
        />
      )}
    </>
  );
}

function SpacesView({ showNotice }: { showNotice: (notice: Notice) => void }) {
  const [spaces, setSpaces] = useState<Space[]>([]);
  const [active, setActive] = useState<Space | null>(null);
  const [files, setFiles] = useState<SpaceFile[]>([]);
  const [members, setMembers] = useState<SpaceMember[]>([]);
  const [ragConfig, setRagConfig] = useState<RagConfig | null>(null);
  const [documents, setDocuments] = useState<RagDocument[]>([]);
  const [tasks, setTasks] = useState<RagTask[]>([]);
  const [ragQuery, setRagQuery] = useState<RagQuery | null>(null);
  const [question, setQuestion] = useState("");
  const [loading, setLoading] = useState(false);
  const [addDocumentOpen, setAddDocumentOpen] = useState(false);

  async function loadSpaces() {
    setLoading(true);
    try {
      const next = await api.listSpaces();
      setSpaces(next || []);
      setActive((current) => current || next?.[0] || null);
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "空间列表加载失败" });
    } finally {
      setLoading(false);
    }
  }

  async function loadSpaceDetail(space: Space | null) {
    if (!space) {
      setFiles([]);
      setMembers([]);
      setRagConfig(null);
      setDocuments([]);
      setTasks([]);
      return;
    }
    setLoading(true);
    try {
      const [nextFiles, nextMembers, nextConfig, nextDocuments, nextTasks] = await Promise.all([
        api.listSpaceFiles(space.id, null),
        api.listMembers(space.id),
        api.ragConfig(space.id),
        api.listRagDocuments(space.id),
        api.listRagTasks(space.id)
      ]);
      setFiles(nextFiles || []);
      setMembers(nextMembers || []);
      setRagConfig(nextConfig);
      setDocuments(nextDocuments || []);
      setTasks(nextTasks || []);
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "空间详情加载失败" });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadSpaces();
  }, []);

  useEffect(() => {
    void loadSpaceDetail(active);
  }, [active?.id]);

  async function createSpace(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const name = String(form.get("name") || "").trim();
    const description = String(form.get("description") || "").trim();
    if (!name) return;
    try {
      const created = await api.createSpace({ name, description });
      setSpaces((items) => [created, ...items]);
      setActive(created);
      event.currentTarget.reset();
      showNotice({ type: "success", text: "空间已创建" });
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "创建空间失败" });
    }
  }

  async function saveRagConfig(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!active) return;
    const form = new FormData(event.currentTarget);
    const enabled = form.get("enabled") === "on" ? 1 : 0;
    const chunkSize = Number(form.get("chunkSize"));
    const chunkOverlap = Number(form.get("chunkOverlap"));
    const topK = Number(form.get("topK"));
    try {
      const next = await api.updateRagConfig(active.id, {
        enabled,
        chunkSize: Number.isFinite(chunkSize) && chunkSize > 0 ? chunkSize : ragConfig?.chunkSize,
        chunkOverlap: Number.isFinite(chunkOverlap) && chunkOverlap >= 0 ? chunkOverlap : ragConfig?.chunkOverlap,
        topK: Number.isFinite(topK) && topK > 0 ? topK : ragConfig?.topK
      });
      setRagConfig(next);
      showNotice({ type: "success", text: "RAG 配置已保存" });
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "RAG 配置保存失败" });
    }
  }

  async function rebuildSpaceRag() {
    if (!active) return;
    try {
      await api.rebuildSpaceRag(active.id);
      await loadSpaceDetail(active);
      showNotice({ type: "success", text: "已提交空间重建任务" });
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "提交重建失败" });
    }
  }

  async function repairSpaceVectors() {
    if (!active) return;
    try {
      await api.repairSpaceVectors(active.id);
      await loadSpaceDetail(active);
      showNotice({ type: "success", text: "已提交向量修复任务" });
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "提交修复失败" });
    }
  }

  async function askRag(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!active || !question.trim()) return;
    setLoading(true);
    try {
      setRagQuery(await api.queryRag(active.id, question.trim(), ragConfig?.topK));
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "RAG 问答失败" });
    } finally {
      setLoading(false);
    }
  }

  return (
    <section className="spaces-view">
      <aside className="spaces-list">
        <div className="section-heading">
          <h2>团队空间</h2>
          <span>{spaces.length} 个空间</span>
        </div>
        <form className="compact-form" onSubmit={createSpace}>
          <input name="name" placeholder="新空间名称" />
          <input name="description" placeholder="描述" />
          <button className="primary-button" type="submit">
            创建
          </button>
        </form>
        <div className="space-card-list">
          {spaces.map((space) => (
            <button
              className={`space-card ${active?.id === space.id ? "active" : ""}`}
              key={space.id}
              type="button"
              onClick={() => setActive(space)}
            >
              <strong>{space.name}</strong>
              <span>{space.description || "暂无描述"}</span>
              <small>{space.role || "MEMBER"} · RAG {space.ragStatus ? "已启用" : "未启用"}</small>
            </button>
          ))}
        </div>
      </aside>

      <div className="spaces-main">
        {!active ? (
          <div className="empty-state compact-empty">
            <div className="empty-icon">
              <Network size={32} />
            </div>
            <h3>还没有空间</h3>
            <p>创建空间后即可管理成员、空间文件和 RAG 索引。</p>
          </div>
        ) : (
          <>
            <div className="space-header">
              <div>
                <h2>{active.name}</h2>
                <p>{active.description || "空间文件、成员、版本与 RAG 检索管理"}</p>
              </div>
              <div className="toolbar-actions">
                <button className="soft-button" type="button" onClick={() => void loadSpaceDetail(active)}>
                  <RefreshCw size={17} />
                  刷新
                </button>
                <button className="primary-button" type="button" onClick={() => setAddDocumentOpen(true)}>
                  <FileText size={17} />
                  添加文档
                </button>
                <button className="soft-button" type="button" onClick={() => void rebuildSpaceRag()}>
                  <Bot size={17} />
                  重建索引
                </button>
                <button className="soft-button" type="button" onClick={() => void repairSpaceVectors()}>
                  <Settings2 size={17} />
                  修复向量
                </button>
              </div>
            </div>

            <div className="space-metrics">
              <div>
                <strong>{files.length}</strong>
                <span>空间文件</span>
              </div>
              <div>
                <strong>{members.length}</strong>
                <span>成员</span>
              </div>
              <div>
                <strong>{documents.length}</strong>
                <span>RAG 文档</span>
              </div>
              <div>
                <strong>{tasks.length}</strong>
                <span>任务</span>
              </div>
            </div>

            <div className="space-grid">
              <section className="space-panel">
                <div className="section-heading">
                  <h3>空间文件</h3>
                  <span>{loading ? "同步中" : `${files.length} 项`}</span>
                </div>
                <div className="compact-list">
                  {files.map((file) => (
                    <div className="compact-row" key={file.id}>
                      <span className={`file-mark ${file.dir ? "folder" : ""}`}>{file.dir ? <Folder size={18} /> : <FileText size={18} />}</span>
                      <div>
                        <strong>{file.name}</strong>
                        <small>{file.dir ? "文件夹" : file.type || "文件"} · {formatSize(file.size)}</small>
                      </div>
                    </div>
                  ))}
                  {!files.length && <p className="muted-line">暂无空间文件</p>}
                </div>
              </section>

              <section className="space-panel">
                <div className="section-heading">
                  <h3>RAG 设置</h3>
                  <span>{ragConfig?.enabled ? "已启用" : "未启用"}</span>
                </div>
                <form className="rag-config-form" onSubmit={saveRagConfig}>
                  <label className="inline-check">
                    <input name="enabled" type="checkbox" defaultChecked={Boolean(ragConfig?.enabled)} />
                    启用 RAG
                  </label>
                  <input name="chunkSize" type="number" min="1" defaultValue={ragConfig?.chunkSize ?? 1000} aria-label="分块大小" />
                  <input name="chunkOverlap" type="number" min="0" defaultValue={ragConfig?.chunkOverlap ?? 100} aria-label="重叠长度" />
                  <input name="topK" type="number" min="1" defaultValue={ragConfig?.topK ?? 5} aria-label="召回数量" />
                  <button className="primary-button" type="submit">
                    保存配置
                  </button>
                </form>
                <dl className="config-facts">
                  <div>
                    <dt>集合</dt>
                    <dd>{ragConfig?.vectorCollection || "-"}</dd>
                  </div>
                  <div>
                    <dt>阈值</dt>
                    <dd>{ragConfig?.scoreThreshold ?? "-"}</dd>
                  </div>
                </dl>
              </section>

              <section className="space-panel">
                <div className="section-heading">
                  <h3>RAG 文档</h3>
                  <span>{documents.length} 条</span>
                </div>
                <div className="compact-list">
                  {documents.slice(0, 8).map((doc) => (
                    <div className="compact-row" key={doc.id}>
                      <span className="file-mark">
                        <FileText size={18} />
                      </span>
                      <div>
                        <strong>{doc.fileName}</strong>
                        <small>{doc.indexStatus || "-"} · {doc.chunkCount ?? 0} chunks</small>
                      </div>
                    </div>
                  ))}
                  {!documents.length && <p className="muted-line">暂无索引文档</p>}
                </div>
              </section>

              <section className="space-panel">
                <div className="section-heading">
                  <h3>空间成员</h3>
                  <span>{members.length} 人</span>
                </div>
                <div className="compact-list">
                  {members.map((member) => (
                    <div className="compact-row" key={`${member.spaceId}-${member.userId}`}>
                      <span className="file-mark">
                        <UsersRound size={18} />
                      </span>
                      <div>
                        <strong>用户 {member.userId}</strong>
                        <small>{member.role || "MEMBER"}</small>
                      </div>
                    </div>
                  ))}
                  {!members.length && <p className="muted-line">暂无成员信息</p>}
                </div>
              </section>
            </div>

            <section className="rag-console">
              <div className="section-heading">
                <h3>空间智能问答</h3>
                <span>{tasks.filter((task) => task.taskStatus === "RUNNING").length} 个运行中任务</span>
              </div>
              <form onSubmit={askRag}>
                <input value={question} onChange={(event) => setQuestion(event.target.value)} placeholder="询问当前空间知识库" />
                <button className="primary-button" type="submit" disabled={loading || !question.trim()}>
                  {loading && <Loader2 className="spin" size={16} />}
                  提问
                </button>
              </form>
              {ragQuery && (
                <div className="rag-answer">
                  <strong>回答</strong>
                  <p>{ragQuery.answer || "暂无回答"}</p>
                  {!!ragQuery.citations?.length && (
                    <div className="citation-list">
                      {ragQuery.citations.map((citation, index) => (
                        <span key={`${citation.chunkId}-${index}`}>{citation.fileName || `引用 ${index + 1}`}</span>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </section>
          </>
        )}
      </div>
      {active && addDocumentOpen && (
        <AddDocumentModal
          space={active}
          onClose={() => setAddDocumentOpen(false)}
          onImported={(count) => {
            showNotice({ type: "success", text: `知识库已新增 ${count} 个文档` });
            void loadSpaceDetail(active);
          }}
          onNotice={showNotice}
        />
      )}
    </section>
  );
}

function ChatView({ showNotice }: { showNotice: (notice: Notice) => void }) {
  const [spaces, setSpaces] = useState<Space[]>([]);
  const [spaceId, setSpaceId] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>([
    {
      id: "welcome",
      role: "assistant",
      content: "选择一个知识库后即可开始提问；也可以保持不选择，用于后续接入通用对话能力。"
    }
  ]);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    api.listSpaces()
      .then((items) => setSpaces(items || []))
      .catch((err) => showNotice({ type: "error", text: err instanceof Error ? err.message : "知识库列表加载失败" }));
  }, []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const question = input.trim();
    if (!question) return;
    setMessages((current) => [...current, { id: `u-${Date.now()}`, role: "user", content: question }]);
    setInput("");

    if (!spaceId) {
      setMessages((current) => [
        ...current,
        {
          id: `a-${Date.now()}`,
          role: "assistant",
          content: "当前未选择知识库。通用空上下文对话接口暂未接入，请先选择一个知识库后提问。"
        }
      ]);
      return;
    }

    setLoading(true);
    try {
      const history = messages
        .slice(-6)
        .filter((message) => !message.id.startsWith("welcome"))
        .filter((message) => message.content.trim())
        .map((message) => ({ role: message.role, content: message.content.trim() }));
      const answer = await api.queryRag(Number(spaceId), question, undefined, history);
      const citations =
        answer.citations?.length
          ? `\n\n引用：${answer.citations.map((citation) => citation.fileName || `片段 ${citation.chunkId}`).join("、")}`
          : "";
      setMessages((current) => [
        ...current,
        { id: `a-${Date.now()}`, role: "assistant", content: `${answer.answer || "暂无回答"}${citations}` }
      ]);
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "知识库问答失败" });
      setMessages((current) => [...current, { id: `a-${Date.now()}`, role: "assistant", content: "这次查询失败了，请稍后重试。" }]);
    } finally {
      setLoading(false);
    }
  }

  return (
    <section className="chat-page">
      <aside className="chat-sidebar">
        <button
          className="primary-button full"
          type="button"
          onClick={() =>
            setMessages([
              {
                id: `welcome-${Date.now()}`,
                role: "assistant",
                content: "新对话已创建。请选择知识库，或保持不选择等待通用问答接口接入。"
              }
            ])
          }
        >
          新建对话
        </button>
        <label>
          知识库范围
          <select value={spaceId} onChange={(event) => setSpaceId(event.target.value)}>
            <option value="">不选择</option>
            {spaces.map((space) => (
              <option key={space.id} value={space.id}>
                {space.name}
              </option>
            ))}
          </select>
        </label>
        <div className="chat-scope-card">
          <strong>{spaceId ? spaces.find((space) => String(space.id) === spaceId)?.name : "空上下文"}</strong>
          <span>{spaceId ? "将调用当前知识库 RAG 接口回答。" : "通用对话接口暂未接入。"}</span>
        </div>
      </aside>

      <div className="chat-main">
        <div className="chat-messages">
          {messages.map((message) => (
            <article className={`chat-message ${message.role}`} key={message.id}>
              <span>{message.role === "user" ? "你" : "AI"}</span>
              <p>{message.content}</p>
            </article>
          ))}
          {loading && (
            <article className="chat-message assistant">
              <span>AI</span>
              <p>
                <Loader2 className="spin inline-spinner" size={16} />
                正在检索知识库
              </p>
            </article>
          )}
        </div>
        <form className="chat-composer" onSubmit={submit}>
          <input value={input} onChange={(event) => setInput(event.target.value)} placeholder="向知识库提问" />
          <button className="primary-button" type="submit" disabled={loading || !input.trim()}>
            发送
          </button>
        </form>
      </div>
    </section>
  );
}

const settingGroupLabels: Record<string, string> = {
  site: "站点信息",
  file: "文件与分享",
  ai: "AI / RAG"
};

function SettingsPanel({ onNotice }: { onNotice: (notice: Notice) => void }) {
  const [settings, setSettings] = useState<SiteSetting[]>([]);
  const [values, setValues] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [activeGroup, setActiveGroup] = useState("site");
  const [error, setError] = useState("");

  async function loadSettings() {
    setLoading(true);
    setError("");
    try {
      const data = await api.adminSettings();
      setSettings(data);
      const nextValues: Record<string, string> = {};
      data.forEach((item) => {
        nextValues[item.key] = item.secret ? "" : item.value ?? "";
      });
      setValues(nextValues);
      if (data.length && !data.some((item) => item.groupName === activeGroup)) {
        setActiveGroup(data[0].groupName);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "设置加载失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadSettings();
  }, []);

  const groups = useMemo(() => Array.from(new Set(settings.map((item) => item.groupName))), [settings]);
  const visibleSettings = settings.filter((item) => item.groupName === activeGroup);

  function updateValue(key: string, value: string) {
    setValues((current) => ({ ...current, [key]: value }));
  }

  async function saveSettings(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setError("");
    try {
      await api.updateAdminSettings(settings.map((item) => ({ key: item.key, value: values[item.key] ?? "" })));
      await loadSettings();
      onNotice({ type: "success", text: "系统设置已保存" });
    } catch (err) {
      const message = err instanceof Error ? err.message : "保存设置失败";
      setError(message);
      onNotice({ type: "error", text: message });
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="settings-panel">
      <div className="settings-head">
        <div>
          <h2>系统设置</h2>
          <p>管理员可在这里维护站点信息、访问地址、注册开关和模型服务配置。</p>
        </div>
        <button className="icon-button" type="button" onClick={() => void loadSettings()} title="刷新设置">
          <RefreshCw size={18} />
        </button>
      </div>

      {loading ? (
        <div className="loading-state">
          <Loader2 className="spin" size={24} />
          正在加载系统设置
        </div>
      ) : error && !settings.length ? (
        <div className="error-state">
          <strong>加载失败</strong>
          <span>{error}</span>
          <button type="button" onClick={() => void loadSettings()}>
            重试
          </button>
        </div>
      ) : (
        <div className="settings-layout">
          <nav className="settings-tabs" aria-label="设置分组">
            {groups.map((group) => (
              <button
                className={activeGroup === group ? "active" : ""}
                key={group}
                type="button"
                onClick={() => setActiveGroup(group)}
              >
                {settingGroupLabels[group] || group}
              </button>
            ))}
          </nav>

          <form className="settings-form" onSubmit={saveSettings}>
            {error && <div className="form-error">{error}</div>}
            {visibleSettings.map((item) => (
              <label className="setting-field" key={item.key}>
                <span>
                  <strong>{item.label || item.key}</strong>
                  <small>{item.description || item.key}</small>
                </span>
                {item.valueType === "boolean" ? (
                  <input
                    type="checkbox"
                    checked={(values[item.key] ?? "").toLowerCase() === "true"}
                    disabled={!item.editable}
                    onChange={(event) => updateValue(item.key, event.target.checked ? "true" : "false")}
                  />
                ) : (
                  <input
                    type={item.secret ? "password" : item.valueType === "number" ? "number" : "text"}
                    value={values[item.key] ?? ""}
                    disabled={!item.editable}
                    placeholder={item.secret ? `${item.maskedValue || "未配置"}，留空则不修改` : item.key}
                    onChange={(event) => updateValue(item.key, event.target.value)}
                  />
                )}
              </label>
            ))}

            <div className="settings-actions">
              <button className="soft-button" type="button" onClick={() => void loadSettings()} disabled={saving}>
                重置
              </button>
              <button className="primary-button" type="submit" disabled={saving}>
                {saving && <Loader2 className="spin" size={16} />}
                保存设置
              </button>
            </div>
          </form>
        </div>
      )}
    </section>
  );
}

function DriveApp({
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
  const [mainView, setMainView] = useState<MainView>(path === "/settings" ? "settings" : path === "/chat" ? "chat" : "files");
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
  const siteName = publicSettings?.siteName || "YL Cloud";
  const effectiveView: MainView = isSettingsPage ? "settings" : isChatPage ? "chat" : mainView === "settings" ? "files" : mainView;

  useEffect(() => {
    if (isSettingsPage) {
      setMainView("settings");
    } else if (isChatPage) {
      setMainView("chat");
    } else if (mainView === "settings") {
      setMainView("files");
    }
  }, [isSettingsPage, isChatPage, mainView]);

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
      : effectiveView === "spaces"
        ? "团队空间"
        : categoryMeta.find((item) => item.key === category)?.label || "全部文件";
  const topbarDescription =
    effectiveView === "settings"
      ? "管理站点信息、访问网址和模型服务配置。"
      : effectiveView === "chat"
        ? "像 ChatGPT 一样提问，可选择知识库作为检索范围。"
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
          <button
            className={effectiveView === "spaces" ? "active" : ""}
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
          {isAdmin && (
            <button
              className={effectiveView === "settings" ? "active" : ""}
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

export function App() {
  const [user, setUser] = useState<User | null>(() => getStoredUser());
  const [path, setPath] = useState(() => window.location.pathname);
  const [publicSettings, setPublicSettings] = useState<PublicSiteSettings | null>(null);

  useEffect(() => {
    api.publicSettings()
      .then(setPublicSettings)
      .catch(() => setPublicSettings(null));
  }, []);

  useEffect(() => {
    const syncPath = () => setPath(window.location.pathname);
    window.addEventListener("popstate", syncPath);
    return () => window.removeEventListener("popstate", syncPath);
  }, []);

  function navigate(nextPath: string) {
    if (window.location.pathname !== nextPath) {
      window.history.pushState(null, "", nextPath);
    }
    setPath(nextPath);
  }

  useEffect(() => {
    if (!user && path !== "/login" && path !== "/sign") {
      navigate("/login");
    }
    if (user && (path === "/login" || path === "/sign")) {
      navigate("/");
    }
  }, [path, user]);

  if (user) {
    return (
      <DriveApp
        user={user}
        publicSettings={publicSettings}
        path={path}
        onNavigate={navigate}
        onLogout={() => {
          setUser(null);
          navigate("/login");
        }}
      />
    );
  }

  return (
    <AuthPage
      mode={path === "/sign" ? "sign" : "login"}
      publicSettings={publicSettings}
      onNavigate={navigate}
      onSignedIn={(nextUser) => {
        setUser(nextUser);
        navigate("/");
      }}
    />
  );
}
