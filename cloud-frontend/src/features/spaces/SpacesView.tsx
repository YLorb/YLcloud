import { ChangeEvent, FormEvent, useEffect, useRef, useState } from "react";
import { Bot, ChevronRight, FileText, Folder, FolderPlus, HardDrive, Loader2, Network, RefreshCw, Settings2, UploadCloud, UsersRound, X } from "lucide-react";
import { api } from "../../api";
import type { Crumb, Notice } from "../../appTypes";
import type { FileItem, RagConfig, RagDocument, RagQuery, RagTask, Space, SpaceFile, SpaceMember } from "../../types";
import { fileIcon, fileTypeLabel, formatSize } from "../../fileUtils";

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

export function SpacesView({ showNotice }: { showNotice: (notice: Notice) => void }) {
  const [spaces, setSpaces] = useState<Space[]>([]);
  const [active, setActive] = useState<Space | null>(null);
  const [files, setFiles] = useState<SpaceFile[]>([]);
  const [members, setMembers] = useState<SpaceMember[]>([]);
  const [ragConfig, setRagConfig] = useState<RagConfig | null>(null);
  const [documents, setDocuments] = useState<RagDocument[]>([]);
  const [tasks, setTasks] = useState<RagTask[]>([]);
  const [ragQuery, setRagQuery] = useState<RagQuery | null>(null);
  const [question, setQuestion] = useState("");
  const [retrievalMode, setRetrievalMode] = useState<"precise" | "balanced" | "broad">("balanced");
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
    if (!active || !canManageRag) return;
    const form = new FormData(event.currentTarget);
    const enabled = form.get("enabled") === "on" ? 1 : 0;
    const chunkSize = Number(form.get("chunkSize"));
    const chunkOverlap = Number(form.get("chunkOverlap"));
    const topK = Number(form.get("topK"));
    const temperature = Number(form.get("temperature"));
    const scoreThreshold = Number(form.get("scoreThreshold"));
    try {
      const next = await api.updateRagConfig(active.id, {
        enabled,
        chunkSize: Number.isFinite(chunkSize) && chunkSize > 0 ? chunkSize : ragConfig?.chunkSize,
        chunkOverlap: Number.isFinite(chunkOverlap) && chunkOverlap >= 0 ? chunkOverlap : ragConfig?.chunkOverlap,
        topK: Number.isFinite(topK) && topK > 0 ? topK : ragConfig?.topK,
        temperature: Number.isFinite(temperature) ? temperature : ragConfig?.temperature,
        scoreThreshold: Number.isFinite(scoreThreshold) ? scoreThreshold : ragConfig?.scoreThreshold
      });
      setRagConfig(next);
      showNotice({ type: "success", text: "知识库设置已保存" });
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "知识库设置保存失败" });
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
      setRagQuery(await api.queryRag(active.id, question.trim(), retrievalMode));
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "RAG 问答失败" });
    } finally {
      setLoading(false);
    }
  }

  const canManageRag = active?.role === "OWNER" || active?.role === "ADMIN";

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

              <section className="space-panel knowledge-settings-page">
                <div className="section-heading">
                  <h3>知识库设置</h3>
                  <span>{canManageRag ? "可编辑" : "只读"}</span>
                </div>
                <form key={`${active.id}-${ragConfig?.updatetime || ragConfig?.id || "new"}`} className="rag-config-form" onSubmit={saveRagConfig}>
                  <label className="inline-check">
                    <input name="enabled" type="checkbox" disabled={!canManageRag} defaultChecked={Boolean(ragConfig?.enabled)} />
                    启用 RAG 问答
                  </label>
                  <label>
                    <span>管理员 Top-k 上限</span>
                    <input name="topK" type="number" min="1" max="20" disabled={!canManageRag} defaultValue={ragConfig?.topK ?? 5} aria-label="召回数量上限" />
                  </label>
                  <label>
                    <span>温度 {Number(ragConfig?.temperature ?? 0.2).toFixed(2)}</span>
                    <input name="temperature" type="range" min="0" max="1" step="0.05" disabled={!canManageRag} defaultValue={ragConfig?.temperature ?? 0.2} aria-label="温度" />
                    <small>低温更依赖上下文，高温更有创造性。</small>
                  </label>
                  <label>
                    <span>分块大小</span>
                    <input name="chunkSize" type="number" min="1" disabled={!canManageRag} defaultValue={ragConfig?.chunkSize ?? 1000} aria-label="分块大小" />
                  </label>
                  <label>
                    <span>重叠长度</span>
                    <input name="chunkOverlap" type="number" min="0" disabled={!canManageRag} defaultValue={ragConfig?.chunkOverlap ?? 100} aria-label="重叠长度" />
                  </label>
                  <label>
                    <span>分数阈值</span>
                    <input name="scoreThreshold" type="number" min="0" max="1" step="0.01" disabled={!canManageRag} defaultValue={ragConfig?.scoreThreshold ?? 0} aria-label="分数阈值" />
                  </label>
                  {canManageRag && (
                    <button className="primary-button" type="submit">
                      保存设置
                    </button>
                  )}
                </form>
                <dl className="config-facts">
                  <div>
                    <dt>集合</dt>
                    <dd>{ragConfig?.vectorCollection || "-"}</dd>
                  </div>
                  <div>
                    <dt>模型</dt>
                    <dd>{ragConfig?.chatModel || "-"}</dd>
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
                <div className="segmented-control" aria-label="检索范围">
                  {[
                    ["precise", "精准"],
                    ["balanced", "均衡"],
                    ["broad", "广泛"]
                  ].map(([mode, label]) => (
                    <button
                      key={mode}
                      className={retrievalMode === mode ? "active" : ""}
                      type="button"
                      onClick={() => setRetrievalMode(mode as "precise" | "balanced" | "broad")}
                    >
                      {label}
                    </button>
                  ))}
                </div>
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
