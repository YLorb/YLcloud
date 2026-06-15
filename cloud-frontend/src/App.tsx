import {
  ArchiveRestore,
  Bot,
  Check,
  ChevronRight,
  CircleUserRound,
  Copy,
  Download,
  File,
  FilePlus2,
  Folder,
  FolderPlus,
  HardDrive,
  Info,
  Link2,
  Loader2,
  LogOut,
  MoreHorizontal,
  Network,
  RefreshCw,
  Search,
  Settings2,
  Share2,
  Shield,
  Sparkles,
  Trash2,
  UploadCloud,
  Users,
  X
} from "lucide-react";
import { FormEvent, ReactNode, useEffect, useMemo, useRef, useState } from "react";
import { api, clearSession, getStoredUser, setSession } from "./api";
import type {
  ChunkUploadInit,
  FileItem,
  FilePreview,
  FileVersion,
  RagConfig,
  RagDocument,
  RagQuery,
  RagTask,
  ShareFile,
  Space,
  SpaceDocumentSearch,
  SpaceFile,
  SpaceMember,
  User
} from "./types";

type Section = "files" | "recycle" | "shares" | "spaces";
type Toast = { tone: "ok" | "warn"; text: string } | null;
type FileDigest = { md5: string; sha1: string };

const sampleFiles: FileItem[] = [
  { fileId: 1, fileUuid: "demo-folder", isDir: true, parentId: 0, name: "项目资料", type: "folder", size: 0 },
  { fileId: 2, fileUuid: "demo-doc", isDir: false, parentId: 0, name: "YLCloud 使用说明.md", type: "md", size: 24800 },
  { fileId: 3, fileUuid: "demo-image", isDir: false, parentId: 0, name: "界面概念图.png", type: "png", size: 822000 }
];

const sampleSpaces: Space[] = [
  { id: 1, name: "研发知识库", description: "团队文档、索引任务与智能问答", role: "OWNER", ragStatus: 1, versionEnabled: 1 },
  { id: 2, name: "课程资料共享", description: "实验报告与学习资料", role: "EDITOR", ragStatus: 0, versionEnabled: 0 }
];

function digestFileInWorker(file: File, chunkSize: number, onProgress: (loaded: number, total: number) => void) {
  return new Promise<FileDigest>((resolve, reject) => {
    const worker = new Worker(new URL("./hashWorker.ts", import.meta.url), { type: "module" });
    worker.onmessage = (event: MessageEvent) => {
      const data = event.data as { type: "progress"; loaded: number; total: number } | ({ type: "done" } & FileDigest);
      if (data.type === "progress") {
        onProgress(data.loaded, data.total);
        return;
      }
      worker.terminate();
      resolve({ md5: data.md5, sha1: data.sha1 });
    };
    worker.onerror = (event) => {
      worker.terminate();
      reject(new Error(event.message || "文件校验失败"));
    };
    worker.postMessage({ file, chunkSize });
  });
}

function uploadRecordKey(parentId: number, file: File, md5: string, sha1: string) {
  return `ylcloud_upload_${parentId}_${file.name}_${file.size}_${md5}_${sha1}`;
}

function createUploadId() {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (char) => {
    const value = Math.floor(Math.random() * 16);
    const next = char === "x" ? value : (value & 0x3) | 0x8;
    return next.toString(16);
  });
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
  return `${value.toFixed(value >= 10 || index === 0 ? 0 : 1)} ${units[index]}`;
}

function fileKind(item: { isDir?: boolean; dir?: boolean; type?: string; name: string }) {
  if (item.isDir || item.dir) return "文件夹";
  return (item.type || item.name.split(".").pop() || "file").toUpperCase();
}

function IconButton({ label, children, onClick, disabled }: { label: string; children: ReactNode; onClick?: () => void; disabled?: boolean }) {
  return (
    <button className="icon-button" type="button" title={label} aria-label={label} onClick={onClick} disabled={disabled}>
      {children}
    </button>
  );
}

function EmptyState({ title, body }: { title: string; body: string }) {
  return (
    <div className="empty-state">
      <div className="empty-mark">
        <Sparkles size={28} />
      </div>
      <h3>{title}</h3>
      <p>{body}</p>
    </div>
  );
}

function ToastBar({ toast, onClose }: { toast: Toast; onClose: () => void }) {
  if (!toast) return null;
  return (
    <div className={`toast ${toast.tone}`}>
      <span>{toast.tone === "ok" ? <Check size={16} /> : <Info size={16} />}</span>
      {toast.text}
      <button type="button" onClick={onClose} aria-label="关闭提示">
        <X size={14} />
      </button>
    </div>
  );
}

function AuthPage({ onLogin }: { onLogin: (user: User) => void }) {
  const [mode, setMode] = useState<"login" | "sign">("login");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    setLoading(true);
    const form = new FormData(event.currentTarget);
    const username = String(form.get("username") || "");
    const password = String(form.get("password") || "");
    const nickname = String(form.get("nickname") || username);
    try {
      if (mode === "sign") {
        await api.sign({ username, password, nickname });
      }
      const user = await api.login(username, password);
      setSession(user);
      onLogin(user);
    } catch (err) {
      setError(err instanceof Error ? err.message : "认证失败，请稍后重试");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="auth-page">
      <section className="auth-visual">
        <div className="brand-lockup">
          <div className="brand-mark">
            <HardDrive size={24} />
          </div>
          <span>YL Cloud</span>
        </div>
        <h1>
          <span className="title-part">安静、清晰、</span>
          <span className="title-part">面向团队知识的</span>
          <span className="title-part">云盘工作台</span>
        </h1>
        <p>
          <span className="subtitle-part">参考 Cloudreve 的文件管理体验，</span>
          <span className="subtitle-part">为当前后端接口重建个人网盘、</span>
          <span className="subtitle-part">空间协作、分享与 RAG 检索入口。</span>
        </p>
        <div className="auth-preview">
          <div className="preview-toolbar">
            <span />
            <span />
            <span />
          </div>
          <div className="preview-row strong">
            <Folder size={20} />
            项目资料
          </div>
          <div className="preview-row">
            <File size={20} />
            YLCloud 使用说明.md
          </div>
          <div className="preview-insight">
            <Bot size={18} />
            已接入空间知识库问答
          </div>
        </div>
      </section>
      <section className="auth-card">
        <div className="auth-tabs">
          <button className={mode === "login" ? "active" : ""} type="button" onClick={() => setMode("login")}>
            登录
          </button>
          <button className={mode === "sign" ? "active" : ""} type="button" onClick={() => setMode("sign")}>
            注册
          </button>
        </div>
        <form onSubmit={submit}>
          {mode === "sign" && (
            <label>
              昵称
              <input name="nickname" placeholder="例如：云盘管理员" autoComplete="nickname" required />
            </label>
          )}
          <label>
            用户名
            <input name="username" placeholder="请输入用户名" autoComplete="username" required />
          </label>
          <label>
            密码
            <input name="password" type="password" placeholder="请输入密码" autoComplete={mode === "login" ? "current-password" : "new-password"} required />
          </label>
          {error && <div className="form-error">{error}</div>}
          <button className="primary-button full" type="submit" disabled={loading}>
            {loading && <Loader2 className="spin" size={16} />}
            {mode === "login" ? "进入云盘" : "创建并进入"}
          </button>
        </form>
      </section>
    </main>
  );
}

function FileTable({
  files,
  selected,
  onSelect,
  onOpen
}: {
  files: FileItem[];
  selected?: FileItem | null;
  onSelect: (file: FileItem) => void;
  onOpen: (file: FileItem) => void;
}) {
  if (!files.length) return <EmptyState title="这里还没有文件" body="上传文件或新建文件夹后，它们会出现在这个列表里。" />;
  return (
    <div className="table-shell">
      <div className="file-table header">
        <span>名称</span>
        <span>类型</span>
        <span>大小</span>
        <span>更新时间</span>
      </div>
      {files.map((file) => (
        <button
          className={`file-table row ${selected?.fileUuid === file.fileUuid ? "selected" : ""}`}
          type="button"
          key={`${file.fileUuid}-${file.fileId}`}
          onClick={() => onSelect(file)}
          onDoubleClick={() => onOpen(file)}
        >
          <span className="file-name">
            <span className={file.isDir ? "file-icon folder" : "file-icon"}>
              {file.isDir ? <Folder size={18} /> : <File size={18} />}
            </span>
            {file.name}
          </span>
          <span>{fileKind(file)}</span>
          <span>{formatSize(file.size)}</span>
          <span>{file.updateTime || file.createTime || "-"}</span>
        </button>
      ))}
    </div>
  );
}

function FileManager({ setToast }: { setToast: (toast: Toast) => void }) {
  const [files, setFiles] = useState<FileItem[]>([]);
  const [parentStack, setParentStack] = useState<Array<{ id: number; name: string }>>([{ id: 0, name: "我的文件" }]);
  const [selected, setSelected] = useState<FileItem | null>(null);
  const [preview, setPreview] = useState<FilePreview | null>(null);
  const [loading, setLoading] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const multipartInput = useRef<HTMLInputElement>(null);
  const parentId = parentStack[parentStack.length - 1]?.id ?? 0;

  async function loadFiles(nextParentId = parentId) {
    setLoading(true);
    try {
      setFiles(await api.listFiles(nextParentId));
    } catch (err) {
      setFiles(sampleFiles.filter((item) => item.parentId === 0));
      setToast({ tone: "warn", text: err instanceof Error ? `后端未连接，显示示例数据：${err.message}` : "后端未连接，显示示例数据" });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    loadFiles(0);
  }, []);

  async function upload(filesToUpload: FileList | null) {
    if (!filesToUpload?.length) return;
    setLoading(true);
    try {
      for (const file of Array.from(filesToUpload)) {
        await api.uploadFile(file, parentId);
      }
      await loadFiles();
      setToast({ tone: "ok", text: "上传完成" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "上传失败" });
    } finally {
      setLoading(false);
      if (fileInput.current) fileInput.current.value = "";
    }
  }

  async function multipartUpload(filesToUpload: FileList | null) {
    const file = filesToUpload?.[0];
    if (!file) return;
    const chunkSize = 5 * 1024 * 1024;
    const totalChunks = Math.ceil(file.size / chunkSize);
    if (totalChunks <= 0) {
      setToast({ tone: "warn", text: "文件大小无效" });
      return;
    }
    setLoading(true);
    try {
      setToast({ tone: "ok", text: "正在校验文件指纹" });
      const digest = await digestFileInWorker(file, chunkSize, (loaded, total) => {
        const percent = Math.floor((loaded / total) * 100);
        setToast({ tone: "ok", text: `文件校验 ${percent}%` });
      });
      const recordKey = uploadRecordKey(parentId, file, digest.md5, digest.sha1);
      const storedUploadId = localStorage.getItem(recordKey);
      const uploadId = storedUploadId || createUploadId();
      const initPayload = {
        fileName: file.name,
        fileMd5: digest.md5,
        fileSha1: digest.sha1,
        fileHash: digest.sha1,
        fileSize: file.size,
        chunkSize,
        totalChunks,
        parentId
      };
      let init: ChunkUploadInit;
      try {
        init = await api.initMultipartUpload({ ...initPayload, uploadId });
      } catch (err) {
        if (!storedUploadId) throw err;
        localStorage.removeItem(recordKey);
        init = await api.initMultipartUpload({ ...initPayload, uploadId: createUploadId() });
      }
      if (init.instantUpload) {
        localStorage.removeItem(recordKey);
        await loadFiles();
        setToast({ tone: "ok", text: "秒传完成" });
        return;
      }
      if (!init.uploadId) {
        throw new Error("后端未返回上传任务 ID");
      }
      localStorage.setItem(recordKey, init.uploadId);
      const uploaded = new Set(init.uploadedChunks || []);
      const status = await api.multipartStatus(init.uploadId).catch(() => null);
      (status?.uploadedChunks || []).forEach((chunk) => uploaded.add(chunk));
      for (let index = 0; index < totalChunks; index += 1) {
        if (uploaded.has(index)) continue;
        const start = index * chunkSize;
        const chunk = file.slice(start, Math.min(file.size, start + chunkSize));
        await api.uploadChunk({ file: chunk, uploadId: init.uploadId, chunkIndex: index });
        setToast({ tone: "ok", text: `分片上传 ${index + 1}/${totalChunks}` });
      }
      await api.mergeMultipartUpload({ uploadId: init.uploadId, fileName: file.name, partNames: [] });
      localStorage.removeItem(recordKey);
      await loadFiles();
      setToast({ tone: "ok", text: "分片上传完成" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "分片上传失败" });
    } finally {
      setLoading(false);
      if (multipartInput.current) multipartInput.current.value = "";
    }
  }

  async function create(isDir: 0 | 1) {
    const name = window.prompt(isDir ? "文件夹名称" : "文件名称");
    if (!name) return;
    try {
      await api.createFile({ isDir, parentId, name, type: isDir ? "folder" : name.split(".").pop() || "txt" });
      await loadFiles();
      setToast({ tone: "ok", text: isDir ? "文件夹已创建" : "文件已创建" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "创建失败" });
    }
  }

  async function rename() {
    if (!selected) return;
    const newName = window.prompt("新的名称", selected.name);
    if (!newName || newName === selected.name) return;
    try {
      await api.renameFile(selected.fileUuid, selected.parentId ?? parentId, newName);
      setSelected(null);
      await loadFiles();
      setToast({ tone: "ok", text: "已重命名" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "重命名失败" });
    }
  }

  async function remove() {
    if (!selected || !window.confirm(`删除「${selected.name}」？文件会进入回收站。`)) return;
    try {
      await api.deleteFile(selected.fileUuid, selected.parentId ?? parentId);
      setSelected(null);
      await loadFiles();
      setToast({ tone: "ok", text: "已移入回收站" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "删除失败" });
    }
  }

  async function share() {
    if (!selected) return;
    try {
      const link = await api.shareFile(selected.fileUuid, selected.parentId ?? parentId);
      await navigator.clipboard?.writeText(link).catch(() => undefined);
      setToast({ tone: "ok", text: `分享链接已生成：${link}` });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "分享失败" });
    }
  }

  async function downloadSelected() {
    if (!selected || selected.isDir) return;
    try {
      await api.downloadFile(selected.fileUuid, selected.parentId ?? parentId, selected.name);
      setToast({ tone: "ok", text: "已开始下载" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "下载失败" });
    }
  }

  async function open(file: FileItem) {
    setSelected(file);
    setPreview(null);
    if (file.isDir) {
      setParentStack((stack) => [...stack, { id: file.fileId, name: file.name }]);
      await loadFiles(file.fileId);
      return;
    }
    try {
      setPreview(await api.previewFile(file.fileUuid, file.parentId ?? parentId));
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "无法预览该文件" });
    }
  }

  function jumpTo(index: number) {
    const next = parentStack.slice(0, index + 1);
    setParentStack(next);
    setSelected(null);
    setPreview(null);
    loadFiles(next[next.length - 1].id);
  }

  return (
    <section className="workspace-grid">
      <div className="main-panel">
        <div className="panel-toolbar">
          <div>
            <div className="breadcrumbs">
              {parentStack.map((part, index) => (
                <button key={`${part.id}-${index}`} type="button" onClick={() => jumpTo(index)}>
                  {part.name}
                  {index < parentStack.length - 1 && <ChevronRight size={14} />}
                </button>
              ))}
            </div>
            <p>{files.length} 个项目</p>
          </div>
          <div className="toolbar-actions">
            <input ref={fileInput} type="file" multiple hidden onChange={(event) => upload(event.target.files)} />
            <input ref={multipartInput} type="file" hidden onChange={(event) => multipartUpload(event.target.files)} />
            <IconButton label="刷新" onClick={() => loadFiles()}>
              <RefreshCw size={18} />
            </IconButton>
            <button className="soft-button" type="button" onClick={() => fileInput.current?.click()}>
              <UploadCloud size={17} />
              上传
            </button>
            <button className="soft-button" type="button" onClick={() => multipartInput.current?.click()}>
              <UploadCloud size={17} />
              分片上传
            </button>
            <button className="soft-button" type="button" onClick={() => create(1)}>
              <FolderPlus size={17} />
              文件夹
            </button>
            <button className="soft-button" type="button" onClick={() => create(0)}>
              <FilePlus2 size={17} />
              新文件
            </button>
          </div>
        </div>
        {loading ? (
          <div className="loading-block">
            <Loader2 className="spin" />
            正在同步文件
          </div>
        ) : (
          <FileTable files={files} selected={selected} onSelect={setSelected} onOpen={open} />
        )}
      </div>
      <aside className="detail-panel">
        {selected ? (
          <>
            <div className="detail-heading">
              <span className={selected.isDir ? "file-icon folder large" : "file-icon large"}>{selected.isDir ? <Folder size={26} /> : <File size={26} />}</span>
              <div>
                <h3>{selected.name}</h3>
                <p>{fileKind(selected)} · {formatSize(selected.size)}</p>
              </div>
            </div>
            <div className="detail-actions">
              <button type="button" onClick={rename}>
                <MoreHorizontal size={16} />
                重命名
              </button>
              <button type="button" onClick={share}>
                <Share2 size={16} />
                分享
              </button>
              <button type="button" onClick={downloadSelected} disabled={selected.isDir}>
                <Download size={16} />
                下载
              </button>
              <button type="button" className="danger" onClick={remove}>
                <Trash2 size={16} />
                删除
              </button>
            </div>
            <dl className="meta-list">
              <div>
                <dt>文件 ID</dt>
                <dd>{selected.fileId}</dd>
              </div>
              <div>
                <dt>UUID</dt>
                <dd>{selected.fileUuid}</dd>
              </div>
              <div>
                <dt>Hash</dt>
                <dd>{selected.hash || "-"}</dd>
              </div>
            </dl>
            {preview && (
              <div className="preview-box">
                <h4>预览</h4>
                {preview.textContent ? <pre>{preview.textContent}</pre> : <p>{preview.previewType || preview.contentType || "后端已返回预览信息"}</p>}
              </div>
            )}
          </>
        ) : (
          <EmptyState title="选择一个文件" body="详情、分享、下载和预览操作会显示在这里。" />
        )}
      </aside>
    </section>
  );
}

function RecycleBin({ setToast }: { setToast: (toast: Toast) => void }) {
  const [files, setFiles] = useState<FileItem[]>([]);
  const [loading, setLoading] = useState(false);

  async function load() {
    setLoading(true);
    try {
      setFiles(await api.listRecycle());
    } catch (err) {
      setFiles([]);
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "无法读取回收站" });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, []);

  async function restore(file: FileItem) {
    try {
      await api.restoreRecycle(file.fileId);
      await load();
      setToast({ tone: "ok", text: "文件已恢复" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "恢复失败" });
    }
  }

  async function purge(file: FileItem) {
    if (!window.confirm(`彻底删除「${file.name}」？此操作不可恢复。`)) return;
    try {
      await api.deleteRecycle(file.fileId);
      await load();
      setToast({ tone: "ok", text: "文件已彻底删除" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "彻底删除失败" });
    }
  }

  return (
    <section className="main-panel solo">
      <div className="panel-toolbar">
        <div>
          <h2>回收站</h2>
          <p>软删除文件可以在这里恢复或彻底清理。</p>
        </div>
        <IconButton label="刷新" onClick={load}>
          <RefreshCw size={18} />
        </IconButton>
      </div>
      {loading ? <div className="loading-block"><Loader2 className="spin" /> 正在读取回收站</div> : null}
      {!loading && !files.length ? <EmptyState title="回收站为空" body="删除后的文件会在这里保留，便于恢复。" /> : null}
      {files.map((file) => (
        <div className="compact-row" key={file.fileId}>
          <span className={file.isDir ? "file-icon folder" : "file-icon"}>{file.isDir ? <Folder size={18} /> : <File size={18} />}</span>
          <strong>{file.name}</strong>
          <span>{formatSize(file.size)}</span>
          <button type="button" onClick={() => restore(file)}>
            <ArchiveRestore size={16} />
            恢复
          </button>
          <button type="button" className="danger" onClick={() => purge(file)}>
            <Trash2 size={16} />
            彻底删除
          </button>
        </div>
      ))}
    </section>
  );
}

function ShareLookup({ setToast }: { setToast: (toast: Toast) => void }) {
  const [code, setCode] = useState("");
  const [share, setShare] = useState<ShareFile | null>(null);
  const [loading, setLoading] = useState(false);

  async function lookup(event: FormEvent) {
    event.preventDefault();
    if (!code.trim()) return;
    setLoading(true);
    try {
      setShare(await api.getShare(code.trim()));
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "分享不存在或已失效" });
      setShare(null);
    } finally {
      setLoading(false);
    }
  }

  const children = share?.children || [];
  return (
    <section className="main-panel solo">
      <div className="panel-toolbar">
        <div>
          <h2>公开分享</h2>
          <p>输入分享码查看公开文件，支持目录子项、下载与预览信息。</p>
        </div>
      </div>
      <form className="lookup-form" onSubmit={lookup}>
        <Search size={18} />
        <input value={code} onChange={(event) => setCode(event.target.value)} placeholder="输入分享码" />
        <button className="primary-button" type="submit" disabled={loading}>
          {loading ? <Loader2 className="spin" size={16} /> : <Link2 size={16} />}
          查看
        </button>
      </form>
      {share ? (
        <div className="share-card">
          <div className="detail-heading">
            <span className={share.dir ? "file-icon folder large" : "file-icon large"}>{share.dir ? <Folder size={26} /> : <File size={26} />}</span>
            <div>
              <h3>{share.name}</h3>
              <p>{fileKind(share)} · {formatSize(share.size)}</p>
            </div>
          </div>
          {share.textContent && <pre className="text-preview">{share.textContent}</pre>}
          {children.map((child) => (
            <div className="compact-row" key={child.fileId}>
              <span className={child.dir ? "file-icon folder" : "file-icon"}>{child.dir ? <Folder size={18} /> : <File size={18} />}</span>
              <strong>{child.name}</strong>
              <span>{formatSize(child.size)}</span>
            </div>
          ))}
          {share.downloadUrl && (
            <a className="primary-button inline" href={share.downloadUrl}>
              <Download size={16} />
              下载分享文件
            </a>
          )}
        </div>
      ) : (
        <EmptyState title="等待分享码" body="公开分享不需要登录，适合临时查看他人共享的文件。" />
      )}
    </section>
  );
}

function SpacesView({ setToast }: { setToast: (toast: Toast) => void }) {
  const [spaces, setSpaces] = useState<Space[]>([]);
  const [active, setActive] = useState<Space | null>(null);
  const [files, setFiles] = useState<SpaceFile[]>([]);
  const [selectedSpaceFile, setSelectedSpaceFile] = useState<SpaceFile | null>(null);
  const [versions, setVersions] = useState<FileVersion[]>([]);
  const versionInput = useRef<HTMLInputElement>(null);
  const [members, setMembers] = useState<SpaceMember[]>([]);
  const [ragConfig, setRagConfig] = useState<RagConfig | null>(null);
  const [documents, setDocuments] = useState<RagDocument[]>([]);
  const [documentHits, setDocumentHits] = useState<SpaceDocumentSearch[]>([]);
  const [tasks, setTasks] = useState<RagTask[]>([]);
  const [query, setQuery] = useState<RagQuery | null>(null);
  const [question, setQuestion] = useState("");
  const [loading, setLoading] = useState(false);

  async function loadSpaces() {
    setLoading(true);
    try {
      const next = await api.listSpaces();
      setSpaces(next);
      setActive((current) => current || next[0] || null);
    } catch (err) {
      setSpaces(sampleSpaces);
      setActive(sampleSpaces[0]);
      setToast({ tone: "warn", text: err instanceof Error ? `后端未连接，显示示例空间：${err.message}` : "后端未连接，显示示例空间" });
    } finally {
      setLoading(false);
    }
  }

  async function loadSpaceDetail(space: Space | null) {
    if (!space) return;
    try {
      const [nextFiles, nextMembers, nextConfig, nextDocuments, nextTasks] = await Promise.all([
        api.listSpaceFiles(space.id, null),
        api.listMembers(space.id),
        api.ragConfig(space.id),
        api.listRagDocuments(space.id),
        api.listRagTasks(space.id)
      ]);
      setFiles(nextFiles);
      setMembers(nextMembers);
      setRagConfig(nextConfig);
      setDocuments(nextDocuments);
      setTasks(nextTasks);
    } catch {
      setFiles([]);
      setMembers([]);
      setRagConfig({ enabled: space.ragStatus ?? 0, topK: 5, chunkSize: 800, chunkOverlap: 120 });
      setDocuments([]);
      setTasks([]);
    }
  }

  async function loadVersions(space: Space | null, file: SpaceFile | null) {
    if (!space || !file || file.dir) {
      setVersions([]);
      return;
    }
    try {
      setVersions(await api.listSpaceFileVersions(space.id, file.id));
    } catch (err) {
      setVersions([]);
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "读取版本列表失败" });
    }
  }

  useEffect(() => {
    loadSpaces();
  }, []);

  useEffect(() => {
    loadSpaceDetail(active);
    setSelectedSpaceFile(null);
    setVersions([]);
    setDocumentHits([]);
  }, [active?.id]);

  async function createSpace(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const name = String(form.get("name") || "");
    const description = String(form.get("description") || "");
    if (!name) return;
    try {
      const created = await api.createSpace({ name, description });
      setSpaces((items) => [created, ...items]);
      setActive(created);
      event.currentTarget.reset();
      setToast({ tone: "ok", text: "空间已创建" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "创建空间失败" });
    }
  }

  async function saveSpace(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!active) return;
    const form = new FormData(event.currentTarget);
    const name = String(form.get("spaceName") || active.name).trim();
    const description = String(form.get("spaceDescription") || "").trim();
    const versionEnabled = form.get("spaceVersionEnabled") === "on" ? 1 : 0;
    try {
      const updated = await api.updateSpace(active.id, { name, description });
      const updatedVersion = await api.updateSpaceVersionSetting(active.id, versionEnabled).catch(() => updated);
      const next = { ...updated, versionEnabled: updatedVersion.versionEnabled ?? versionEnabled };
      setActive(next);
      setSpaces((items) => items.map((item) => (item.id === next.id ? next : item)));
      setToast({ tone: "ok", text: "空间设置已保存" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "保存空间设置失败" });
    }
  }

  async function deleteActiveSpace() {
    if (!active || !window.confirm(`删除空间「${active.name}」？`)) return;
    try {
      await api.deleteSpace(active.id);
      const nextSpaces = spaces.filter((space) => space.id !== active.id);
      setSpaces(nextSpaces);
      setActive(nextSpaces[0] || null);
      setToast({ tone: "ok", text: "空间已删除" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "删除空间失败" });
    }
  }

  async function askRag(event: FormEvent) {
    event.preventDefault();
    if (!active || !question.trim()) return;
    try {
      setQuery(await api.queryRag(active.id, question.trim(), ragConfig?.topK));
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "问答失败" });
    }
  }

  async function rebuild() {
    if (!active) return;
    try {
      await api.rebuildSpaceRag(active.id);
      setToast({ tone: "ok", text: "已提交索引重建任务" });
      await loadSpaceDetail(active);
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "重建失败" });
    }
  }

  async function rebuildSelectedFileRag() {
    if (!active || !selectedSpaceFile) return;
    try {
      await api.rebuildFileRag(active.id, selectedSpaceFile.id);
      await loadSpaceDetail(active);
      setToast({ tone: "ok", text: "已提交单文件索引重建任务" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "单文件重建失败" });
    }
  }

  async function repairSelectedFileVectors() {
    if (!active || !selectedSpaceFile) return;
    try {
      await api.repairFileVectors(active.id, selectedSpaceFile.id);
      await loadSpaceDetail(active);
      setToast({ tone: "ok", text: "已提交单文件向量修复" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "单文件向量修复失败" });
    }
  }

  async function repairSpaceVectors() {
    if (!active) return;
    try {
      await api.repairSpaceVectors(active.id);
      await loadSpaceDetail(active);
      setToast({ tone: "ok", text: "已提交空间向量修复" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "空间向量修复失败" });
    }
  }

  async function searchDocuments(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!active) return;
    const form = new FormData(event.currentTarget);
    const keyword = String(form.get("keyword") || "").trim();
    const indexStatus = String(form.get("indexStatus") || "").trim();
    try {
      setDocumentHits(await api.searchRagDocuments(active.id, { keyword, indexStatus, searchContent: 1, page: 1, pageSize: 20 }));
      setToast({ tone: "ok", text: "文档搜索完成" });
    } catch (err) {
      setDocumentHits([]);
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "文档搜索失败" });
    }
  }

  async function retryTask(task: RagTask) {
    if (!active) return;
    try {
      await api.retryRagTask(active.id, task.id);
      await loadSpaceDetail(active);
      setToast({ tone: "ok", text: "任务已重试" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "任务重试失败" });
    }
  }

  async function retryFailedTasks() {
    if (!active) return;
    try {
      await api.retryFailedRagTasks(active.id);
      await loadSpaceDetail(active);
      setToast({ tone: "ok", text: "失败任务已批量重试" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "批量重试失败" });
    }
  }

  async function createFolder(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!active) return;
    const form = new FormData(event.currentTarget);
    const name = String(form.get("folderName") || "").trim();
    if (!name) return;
    try {
      await api.createSpaceFolder(active.id, { name, parentId: null });
      event.currentTarget.reset();
      await loadSpaceDetail(active);
      setToast({ tone: "ok", text: "空间文件夹已创建" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "创建空间文件夹失败" });
    }
  }

  async function importFile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!active) return;
    const form = new FormData(event.currentTarget);
    const userFileId = Number(form.get("userFileId"));
    const name = String(form.get("importName") || "").trim();
    if (!Number.isFinite(userFileId) || userFileId <= 0) return;
    try {
      await api.importSpaceFile(active.id, { userFileId, parentId: null, name: name || undefined });
      event.currentTarget.reset();
      await loadSpaceDetail(active);
      setToast({ tone: "ok", text: "个人文件已导入空间" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "导入文件失败" });
    }
  }

  async function removeSpaceFile(file: SpaceFile) {
    if (!active || !window.confirm(`从空间移除「${file.name}」？`)) return;
    try {
      await api.removeSpaceFile(active.id, file.id);
      if (selectedSpaceFile?.id === file.id) {
        setSelectedSpaceFile(null);
        setVersions([]);
      }
      await loadSpaceDetail(active);
      setToast({ tone: "ok", text: "空间文件已移除" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "移除空间文件失败" });
    }
  }

  async function selectSpaceFile(file: SpaceFile) {
    setSelectedSpaceFile(file);
    await loadVersions(active, file);
  }

  async function toggleSpaceFileVersion(file: SpaceFile) {
    if (!active) return;
    const nextEnabled = file.effectiveVersionEnabled ? 0 : 1;
    try {
      await api.updateSpaceFileVersionSetting(active.id, file.id, nextEnabled);
      await loadSpaceDetail(active);
      setToast({ tone: "ok", text: "文件版本设置已更新" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "更新文件版本设置失败" });
    }
  }

  async function previewSpaceFile(file: SpaceFile) {
    if (!active || file.dir) return;
    try {
      const info = await api.previewSpaceFile(active.id, file.id);
      setToast({ tone: "ok", text: info.textContent || info.previewType || "已获取空间文件预览信息" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "空间文件预览失败" });
    }
  }

  async function downloadSpaceFile(file: SpaceFile) {
    if (!active || file.dir) return;
    try {
      await api.downloadSpaceFile(active.id, file.id, file.name);
      setToast({ tone: "ok", text: "已开始下载空间文件" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "空间文件下载失败" });
    }
  }

  async function uploadVersion(filesToUpload: FileList | null) {
    const file = filesToUpload?.[0];
    if (!active || !selectedSpaceFile || selectedSpaceFile.dir || !file) return;
    const changeNote = window.prompt("版本说明，可留空") || undefined;
    try {
      await api.uploadSpaceFileVersion(active.id, selectedSpaceFile.id, file, changeNote);
      await loadVersions(active, selectedSpaceFile);
      await loadSpaceDetail(active);
      setToast({ tone: "ok", text: "新版本已上传" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "上传版本失败" });
    } finally {
      if (versionInput.current) versionInput.current.value = "";
    }
  }

  async function restoreVersion(version: FileVersion) {
    if (!active || !selectedSpaceFile || !window.confirm(`恢复到版本 ${version.versionNo ?? version.id}？`)) return;
    const changeNote = window.prompt("恢复说明，可留空") || undefined;
    try {
      await api.restoreSpaceFileVersion(active.id, selectedSpaceFile.id, version.id, changeNote);
      await loadVersions(active, selectedSpaceFile);
      await loadSpaceDetail(active);
      setToast({ tone: "ok", text: "版本已恢复" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "版本恢复失败" });
    }
  }

  async function addMember(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!active) return;
    const form = new FormData(event.currentTarget);
    const userId = Number(form.get("userId"));
    const role = String(form.get("role") || "VIEWER");
    if (!Number.isFinite(userId) || userId <= 0) return;
    try {
      await api.addMember(active.id, { userId, role });
      event.currentTarget.reset();
      await loadSpaceDetail(active);
      setToast({ tone: "ok", text: "成员已添加" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "添加成员失败" });
    }
  }

  async function updateMember(member: SpaceMember) {
    if (!active) return;
    const role = window.prompt("新的成员角色", member.role || "VIEWER");
    if (!role || role === member.role) return;
    try {
      await api.updateMemberRole(active.id, member.userId, role);
      await loadSpaceDetail(active);
      setToast({ tone: "ok", text: "成员角色已更新" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "更新成员角色失败" });
    }
  }

  async function removeMember(member: SpaceMember) {
    if (!active || !window.confirm(`移除用户 ${member.userId}？`)) return;
    try {
      await api.removeMember(active.id, member.userId);
      await loadSpaceDetail(active);
      setToast({ tone: "ok", text: "成员已移除" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "移除成员失败" });
    }
  }

  async function saveRagConfig(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!active) return;
    const form = new FormData(event.currentTarget);
    const enabled = form.get("enabled") === "on" ? 1 : 0;
    const topK = Number(form.get("topK"));
    const chunkSize = Number(form.get("chunkSize"));
    const chunkOverlap = Number(form.get("chunkOverlap"));
    try {
      const nextConfig = await api.updateRagConfig(active.id, {
        enabled,
        topK: Number.isFinite(topK) && topK > 0 ? topK : ragConfig?.topK,
        chunkSize: Number.isFinite(chunkSize) && chunkSize > 0 ? chunkSize : ragConfig?.chunkSize,
        chunkOverlap: Number.isFinite(chunkOverlap) && chunkOverlap >= 0 ? chunkOverlap : ragConfig?.chunkOverlap
      });
      setRagConfig(nextConfig);
      setToast({ tone: "ok", text: "RAG 配置已更新" });
    } catch (err) {
      setToast({ tone: "warn", text: err instanceof Error ? err.message : "更新 RAG 配置失败" });
    }
  }

  return (
    <section className="spaces-layout">
      <aside className="space-list">
        <div className="panel-toolbar compact">
          <div>
            <h2>团队空间</h2>
            <p>{spaces.length} 个空间</p>
          </div>
          {loading && <Loader2 className="spin" size={18} />}
        </div>
        <form className="mini-form" onSubmit={createSpace}>
          <input name="name" placeholder="新空间名称" />
          <input name="description" placeholder="描述" />
          <button type="submit">
            <FolderPlus size={16} />
            创建
          </button>
        </form>
        {spaces.map((space) => (
          <button className={`space-card ${active?.id === space.id ? "active" : ""}`} key={space.id} type="button" onClick={() => setActive(space)}>
            <strong>{space.name}</strong>
            <span>{space.description || "暂无描述"}</span>
            <small>{space.role || "MEMBER"} · RAG {space.ragStatus ? "已启用" : "未启用"}</small>
          </button>
        ))}
      </aside>
      <div className="main-panel">
        {active ? (
          <>
            <div className="panel-toolbar">
              <div>
                <h2>{active.name}</h2>
                <p>{active.description || "团队协作文件、历史版本与智能检索空间。"}</p>
              </div>
              <button className="soft-button" type="button" onClick={rebuild}>
                <RefreshCw size={17} />
                重建索引
              </button>
            </div>
            <form className="settings-strip" onSubmit={saveSpace}>
              <input name="spaceName" defaultValue={active.name} aria-label="空间名称" />
              <input name="spaceDescription" defaultValue={active.description || ""} aria-label="空间描述" />
              <label>
                <input name="spaceVersionEnabled" type="checkbox" defaultChecked={Boolean(active.versionEnabled)} />
                空间版本
              </label>
              <button type="submit">保存空间</button>
              <button type="button" className="danger" onClick={deleteActiveSpace}>
                删除空间
              </button>
            </form>
            <div className="metrics-grid">
              <div>
                <span>空间文件</span>
                <strong>{files.length}</strong>
              </div>
              <div>
                <span>成员</span>
                <strong>{members.length}</strong>
              </div>
              <div>
                <span>索引文档</span>
                <strong>{documents.length}</strong>
              </div>
              <div>
                <span>任务</span>
                <strong>{tasks.length}</strong>
              </div>
            </div>
            <div className="split-panels">
              <div className="sub-panel">
                <h3>空间文件</h3>
                <div className="stacked-forms">
                  <form className="inline-form" onSubmit={createFolder}>
                    <input name="folderName" placeholder="新文件夹名称" />
                    <button type="submit">
                      <FolderPlus size={16} />
                      新建
                    </button>
                  </form>
                  <form className="inline-form" onSubmit={importFile}>
                    <input name="userFileId" type="number" min="1" placeholder="个人文件 ID" />
                    <input name="importName" placeholder="导入后名称，可选" />
                    <button type="submit">
                      <Copy size={16} />
                      导入
                    </button>
                  </form>
                </div>
                {!files.length ? <EmptyState title="暂无空间文件" body="可以从个人文件导入，或在后端接口扩展上传入口。" /> : null}
                {files.map((file) => (
                  <div className={`compact-row ${selectedSpaceFile?.id === file.id ? "selected" : ""}`} key={file.id}>
                    <span className={file.dir ? "file-icon folder" : "file-icon"}>{file.dir ? <Folder size={18} /> : <File size={18} />}</span>
                    <button className="row-title-button" type="button" onClick={() => selectSpaceFile(file)}>
                      {file.name}
                    </button>
                    <span>{file.effectiveVersionEnabled ? "版本开启" : "版本继承"}</span>
                    <button type="button" onClick={() => previewSpaceFile(file)} disabled={file.dir}>
                      <Search size={15} />
                      预览
                    </button>
                    <button type="button" onClick={() => downloadSpaceFile(file)} disabled={file.dir}>
                      <Download size={15} />
                      下载
                    </button>
                    <button type="button" onClick={() => toggleSpaceFileVersion(file)}>
                      <Settings2 size={15} />
                      版本
                    </button>
                    <button type="button" onClick={() => removeSpaceFile(file)}>
                      <Trash2 size={15} />
                      移除
                    </button>
                  </div>
                ))}
                {selectedSpaceFile && !selectedSpaceFile.dir ? (
                  <div className="version-panel">
                    <div className="panel-mini-header">
                      <strong>{selectedSpaceFile.name}</strong>
                      <span>{versions.length} 个版本</span>
                    </div>
                    <input ref={versionInput} type="file" hidden onChange={(event) => uploadVersion(event.target.files)} />
                    <div className="row-actions">
                      <button type="button" onClick={() => versionInput.current?.click()}>
                        <UploadCloud size={15} />
                        上传新版本
                      </button>
                      <button type="button" onClick={rebuildSelectedFileRag}>
                        <RefreshCw size={15} />
                        重建此文件
                      </button>
                      <button type="button" onClick={repairSelectedFileVectors}>
                        <Settings2 size={15} />
                        修复向量
                      </button>
                    </div>
                    {!versions.length ? <p className="muted-line">暂无版本记录</p> : null}
                    {versions.map((version) => (
                      <div className="compact-row version-row" key={version.id}>
                        <span className="file-icon">
                          <File size={16} />
                        </span>
                        <strong>v{version.versionNo ?? version.id}</strong>
                        <span>{version.current ? "当前" : formatSize(version.fileSize)}</span>
                        <button type="button" onClick={() => api.downloadSpaceFileVersion(active.id, selectedSpaceFile.id, version.id, version.fileName || selectedSpaceFile.name)}>
                          <Download size={15} />
                          下载
                        </button>
                        <button type="button" onClick={() => restoreVersion(version)}>
                          <ArchiveRestore size={15} />
                          恢复
                        </button>
                      </div>
                    ))}
                  </div>
                ) : null}
              </div>
              <div className="sub-panel">
                <h3>
                  <Settings2 size={18} />
                  RAG 设置
                </h3>
                <form className="config-form" onSubmit={saveRagConfig}>
                  <label>
                    <input name="enabled" type="checkbox" defaultChecked={Boolean(ragConfig?.enabled)} />
                    启用 RAG
                  </label>
                  <input name="chunkSize" type="number" min="1" defaultValue={ragConfig?.chunkSize ?? 800} aria-label="分块大小" />
                  <input name="chunkOverlap" type="number" min="0" defaultValue={ragConfig?.chunkOverlap ?? 120} aria-label="重叠长度" />
                  <input name="topK" type="number" min="1" defaultValue={ragConfig?.topK ?? 5} aria-label="召回数量" />
                  <button type="submit">保存配置</button>
                </form>
                <dl className="meta-list flat">
                  <div><dt>启用</dt><dd>{ragConfig?.enabled ? "是" : "否"}</dd></div>
                  <div><dt>分块</dt><dd>{ragConfig?.chunkSize || "-"} / {ragConfig?.chunkOverlap || "-"}</dd></div>
                  <div><dt>TopK</dt><dd>{ragConfig?.topK || "-"}</dd></div>
                  <div><dt>向量集合</dt><dd>{ragConfig?.vectorCollection || "-"}</dd></div>
                </dl>
              </div>
            </div>
            <div className="sub-panel member-panel">
              <h3>
                <Users size={18} />
                空间成员
              </h3>
              <form className="inline-form" onSubmit={addMember}>
                <input name="userId" type="number" min="1" placeholder="用户 ID" />
                <select name="role" defaultValue="VIEWER" aria-label="成员角色">
                  <option value="VIEWER">VIEWER</option>
                  <option value="EDITOR">EDITOR</option>
                  <option value="ADMIN">ADMIN</option>
                </select>
                <button type="submit">
                  <Users size={16} />
                  添加
                </button>
              </form>
              {!members.length ? <p className="muted-line">暂无成员信息</p> : null}
              {members.map((member) => (
                <div className="compact-row" key={`${member.spaceId}-${member.userId}`}>
                  <span className="file-icon">
                    <CircleUserRound size={18} />
                  </span>
                  <strong>用户 {member.userId}</strong>
                  <span>{member.role || "VIEWER"}</span>
                  <span>{member.status === 0 ? "禁用" : "正常"}</span>
                  <button type="button" onClick={() => updateMember(member)}>
                    <Settings2 size={15} />
                    改角色
                  </button>
                  <button type="button" className="danger" onClick={() => removeMember(member)}>
                    <Trash2 size={15} />
                    移除
                  </button>
                </div>
              ))}
            </div>
            <div className="split-panels ops-panels">
              <div className="sub-panel">
                <h3>RAG 文档检索</h3>
                <form className="inline-form" onSubmit={searchDocuments}>
                  <input name="keyword" placeholder="关键词" />
                  <select name="indexStatus" defaultValue="" aria-label="索引状态">
                    <option value="">全部状态</option>
                    <option value="SUCCESS">SUCCESS</option>
                    <option value="FAILED">FAILED</option>
                    <option value="PENDING">PENDING</option>
                    <option value="PROCESSING">PROCESSING</option>
                  </select>
                  <button type="submit">
                    <Search size={16} />
                    搜索
                  </button>
                </form>
                {[...documentHits, ...documents.filter((doc) => !documentHits.some((hit) => hit.documentId === doc.id))].slice(0, 8).map((doc) => (
                  <div className="compact-row" key={`doc-${"documentId" in doc ? doc.documentId : doc.id}`}>
                    <span className="file-icon">
                      <File size={16} />
                    </span>
                    <strong>{doc.fileName}</strong>
                    <span>{doc.indexStatus || "-"}</span>
                    <span>{doc.chunkCount ?? 0} chunks</span>
                  </div>
                ))}
              </div>
              <div className="sub-panel">
                <div className="panel-mini-header">
                  <h3>RAG 任务</h3>
                  <div className="row-actions">
                    <button type="button" onClick={retryFailedTasks}>
                      <RefreshCw size={15} />
                      重试失败
                    </button>
                    <button type="button" onClick={repairSpaceVectors}>
                      <Settings2 size={15} />
                      修复空间向量
                    </button>
                  </div>
                </div>
                {!tasks.length ? <p className="muted-line">暂无任务</p> : null}
                {tasks.slice(0, 8).map((task) => (
                  <div className="compact-row" key={task.id}>
                    <span className="file-icon">
                      <Bot size={16} />
                    </span>
                    <strong>{task.taskType || `任务 ${task.id}`}</strong>
                    <span>{task.taskStatus || "-"}</span>
                    <button type="button" onClick={() => retryTask(task)}>
                      <RefreshCw size={15} />
                      重试
                    </button>
                  </div>
                ))}
              </div>
            </div>
            <div className="rag-console">
              <div>
                <h3>空间智能问答</h3>
                <p>基于当前空间索引文档检索上下文并生成回答。</p>
              </div>
              <form onSubmit={askRag}>
                <input value={question} onChange={(event) => setQuestion(event.target.value)} placeholder="例如：总结这个空间里的实验报告结论" />
                <button className="primary-button" type="submit">
                  <Bot size={16} />
                  提问
                </button>
              </form>
              {query?.answer && (
                <div className="answer-box">
                  <strong>{query.question}</strong>
                  <p>{query.answer}</p>
                  {(query.citations || []).map((citation) => (
                    <span className="citation" key={`${citation.documentId}-${citation.chunkId}`}>{citation.fileName || "引用文档"}</span>
                  ))}
                </div>
              )}
            </div>
          </>
        ) : (
          <EmptyState title="还没有空间" body="创建一个团队空间后，即可管理成员、版本和 RAG 索引。" />
        )}
      </div>
    </section>
  );
}

function AppShell({ user, onLogout }: { user: User; onLogout: () => void }) {
  const [section, setSection] = useState<Section>("files");
  const [toast, setToast] = useState<Toast>(null);
  const nav = [
    { id: "files" as Section, label: "我的文件", icon: <HardDrive size={18} /> },
    { id: "recycle" as Section, label: "回收站", icon: <Trash2 size={18} /> },
    { id: "shares" as Section, label: "公开分享", icon: <Share2 size={18} /> },
    { id: "spaces" as Section, label: "团队空间", icon: <Network size={18} /> }
  ];

  const subtitle = useMemo(() => {
    if (section === "files") return "上传、整理、分享和预览个人文件";
    if (section === "recycle") return "恢复误删文件或执行清理";
    if (section === "shares") return "查看公开分享内容";
    return "成员协作、版本管理和 RAG 问答";
  }, [section]);

  return (
    <main className="app-shell">
      <aside className="sidebar">
        <div className="brand-lockup small">
          <div className="brand-mark">
            <HardDrive size={20} />
          </div>
          <span>YL Cloud</span>
        </div>
        <nav>
          {nav.map((item) => (
            <button className={section === item.id ? "active" : ""} type="button" key={item.id} onClick={() => setSection(item.id)}>
              {item.icon}
              {item.label}
            </button>
          ))}
        </nav>
        <div className="sidebar-note">
          <Shield size={17} />
          <span>Token 鉴权已启用</span>
        </div>
      </aside>
      <section className="content">
        <header className="topbar">
          <div>
            <h1>{nav.find((item) => item.id === section)?.label}</h1>
            <p>{subtitle}</p>
          </div>
          <div className="topbar-actions">
            <div className="user-chip">
              <CircleUserRound size={18} />
              {user.nickname || user.username}
            </div>
            <IconButton label="退出登录" onClick={onLogout}>
              <LogOut size={18} />
            </IconButton>
          </div>
        </header>
        {section === "files" && <FileManager setToast={setToast} />}
        {section === "recycle" && <RecycleBin setToast={setToast} />}
        {section === "shares" && <ShareLookup setToast={setToast} />}
        {section === "spaces" && <SpacesView setToast={setToast} />}
      </section>
      <ToastBar toast={toast} onClose={() => setToast(null)} />
    </main>
  );
}

export function App() {
  const [user, setUser] = useState<User | null>(() => getStoredUser());

  function logout() {
    clearSession();
    setUser(null);
  }

  return user ? <AppShell user={user} onLogout={logout} /> : <AuthPage onLogin={setUser} />;
}
