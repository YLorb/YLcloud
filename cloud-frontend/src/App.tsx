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
type MainView = "files" | "spaces" | "settings";
type Category = "all" | "images" | "documents" | "videos" | "recycle";
type Notice = { type: "success" | "error" | "info"; text: string } | null;
type Crumb = { id: number; name: string };

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
  category,
  onSelect,
  onOpen,
  onPreview,
  onDownload,
  onRename,
  onDelete,
  onRestore,
  onDeleteForever
}: {
  files: FileItem[];
  selected: FileItem | null;
  category: Category;
  onSelect: (item: FileItem) => void;
  onOpen: (item: FileItem) => void;
  onPreview: (item: FileItem) => void;
  onDownload: (item: FileItem) => void;
  onRename: (item: FileItem) => void;
  onDelete: (item: FileItem) => void;
  onRestore: (item: FileItem) => void;
  onDeleteForever: (item: FileItem) => void;
}) {
  if (!files.length) return <EmptyState category={category} />;

  return (
    <div className="table-card">
      <div className="file-row table-head">
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
          role="button"
          tabIndex={0}
          onKeyDown={(event) => {
            if (event.key === "Enter") onOpen(item);
          }}
        >
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
  const [mainView, setMainView] = useState<MainView>(path === "/settings" ? "settings" : "files");
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
  const uploadInput = useRef<HTMLInputElement>(null);
  const parentId = crumbs[crumbs.length - 1]?.id ?? 0;
  const isAdmin = user.role?.toUpperCase() === "ADMIN";
  const isSettingsPage = isAdmin && path === "/settings";
  const siteName = publicSettings?.siteName || "YL Cloud";
  const effectiveView: MainView = isSettingsPage ? "settings" : mainView === "settings" ? "files" : mainView;

  useEffect(() => {
    if (isSettingsPage) {
      setMainView("settings");
    } else if (mainView === "settings") {
      setMainView("files");
    }
  }, [isSettingsPage, mainView]);

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

  function logout() {
    clearSession();
    onLogout();
  }

  const topbarTitle =
    effectiveView === "settings"
      ? "系统设置"
      : effectiveView === "spaces"
        ? "团队空间"
        : categoryMeta.find((item) => item.key === category)?.label || "全部文件";
  const topbarDescription =
    effectiveView === "settings"
      ? "管理站点信息、访问网址和模型服务配置。"
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
                    category={category}
                    onSelect={setSelected}
                    onOpen={(item) => void openItem(item)}
                    onPreview={(item) => void previewItem(item)}
                    onDownload={(item) => void downloadFile(item)}
                    onRename={(item) => void renameFile(item)}
                    onDelete={(item) => void deleteFile(item)}
                    onRestore={(item) => void restoreFile(item)}
                    onDeleteForever={(item) => void deleteForever(item)}
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
