import { FileSearch, Image, MonitorPlay, Search, Settings2 } from "lucide-react";
import { useState, type FormEvent } from "react";
import { toast } from "sonner";
import { Button } from "../../../components/ui/Button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../../../components/ui/Tabs";
import { AdminField, AdminFormDialog } from "../components/AdminDialogs";
import { AdminFilterBar, AdminPage, AdminSearch, AdminSection, AdminSelect, AdminStat, AdminStats } from "../components/AdminPage";
import { AdminPagination, AdminTable } from "../components/AdminTable";

type Mapping = { id: string; extension: string; label: string; application: string; status: string };

export function FileSystemPage() {
  const [query, setQuery] = useState("");
  const [submitted, setSubmitted] = useState("");
  const [mode, setMode] = useState("all");
  const [scope, setScope] = useState("all");
  const [maxSize, setMaxSize] = useState("");
  const [previewLimit, setPreviewLimit] = useState("");
  const [icons, setIcons] = useState<Mapping[]>([]);
  const [apps, setApps] = useState<Mapping[]>([]);
  const [dialog, setDialog] = useState<"icons" | "apps" | null>(null);
  const [extension, setExtension] = useState("");
  const [label, setLabel] = useState("");
  const [application, setApplication] = useState("");

  function search(event: FormEvent) {
    event.preventDefault();
    setSubmitted(query.trim());
  }

  function addMapping(event: FormEvent) {
    event.preventDefault();
    const record = { id: crypto.randomUUID(), extension: extension.trim(), label: label.trim(), application: application.trim(), status: "已启用" };
    if (dialog === "icons") setIcons((current) => [...current, record]);
    else setApps((current) => [...current, record]);
    setDialog(null);
    setExtension(""); setLabel(""); setApplication("");
    toast.success("演示数据已更新，未保存到服务器");
  }

  return <AdminPage eyebrow="文件管理" title="文件系统" description="设计文件处理、全文搜索、图标和在线浏览应用的管理入口。">
    <Tabs defaultValue="search" className="admin-horizontal-tabs">
      <TabsList className="admin-tabs-list" aria-label="文件系统分类">
        <TabsTrigger value="search"><FileSearch size={15} />全文搜索</TabsTrigger>
        <TabsTrigger value="params"><Settings2 size={15} />参数设置</TabsTrigger>
        <TabsTrigger value="icons"><Image size={15} />文件图标</TabsTrigger>
        <TabsTrigger value="apps"><MonitorPlay size={15} />在线浏览应用</TabsTrigger>
      </TabsList>
      <TabsContent value="search">
        <AdminSection title="全文搜索" description="检索模式与范围均为前端演示；不会查询 Qdrant 或读取真实文件。">
          <form className="admin-search-form" onSubmit={search}>
            <AdminFilterBar>
              <AdminSearch value={query} onChange={setQuery} label="搜索文件内容" placeholder="输入文件名、关键词或内容" />
              <AdminSelect label="检索方式" value={mode} onChange={setMode} options={[{ value: "all", label: "综合检索" }, { value: "keyword", label: "关键词" }, { value: "semantic", label: "语义" }]} />
              <AdminSelect label="范围" value={scope} onChange={setScope} options={[{ value: "all", label: "全部空间" }, { value: "personal", label: "个人文件" }, { value: "space", label: "团队空间" }]} />
              <Button variant="primary" type="submit"><Search size={15} />搜索</Button>
            </AdminFilterBar>
          </form>
          <div className="admin-search-summary">当前条件：{submitted ? `“${submitted}”` : "尚未搜索"} · {mode === "all" ? "综合检索" : mode === "keyword" ? "关键词" : "语义"} · {scope === "all" ? "全部空间" : scope === "personal" ? "个人文件" : "团队空间"} · 0 个结果</div>
          <AdminTable columns={[{ key: "file", label: "文件", render: () => null }, { key: "space", label: "所属空间", render: () => null }, { key: "match", label: "匹配方式", render: () => null }, { key: "evidence", label: "检索证据", render: () => null }]} items={[]} getKey={() => ""} emptyTitle="暂无检索结果" emptyMessage="本阶段仅展示搜索页面结构，不会查询服务器索引。" />
          <AdminPagination />
        </AdminSection>
      </TabsContent>
      <TabsContent value="params">
        <AdminSection title="文件系统参数" description="定义上传、预览与文件类型处理的管理表单。">
          <div className="admin-settings-form">
            <AdminField label="单文件上传上限（MB）" hint="仅演示字段；不影响真实上传限制。"><input type="number" min="0" value={maxSize} onChange={(event) => setMaxSize(event.target.value)} placeholder="未读取服务器配置" /></AdminField>
            <AdminField label="在线预览大小上限（MB）"><input type="number" min="0" value={previewLimit} onChange={(event) => setPreviewLimit(event.target.value)} placeholder="未读取服务器配置" /></AdminField>
            <AdminField label="未知文件类型"><select defaultValue="download"><option value="download">下载文件</option><option value="reject">禁止预览</option></select></AdminField>
            <Button variant="confirm" onClick={() => toast.success("演示参数已更新，未保存到服务器")}>保存演示参数</Button>
          </div>
        </AdminSection>
      </TabsContent>
      <TabsContent value="icons">
        <MappingPanel kind="icons" items={icons} onAdd={() => setDialog("icons")} />
      </TabsContent>
      <TabsContent value="apps">
        <MappingPanel kind="apps" items={apps} onAdd={() => setDialog("apps")} />
      </TabsContent>
    </Tabs>
    <AdminFormDialog open={dialog !== null} onOpenChange={(open) => !open && setDialog(null)} title={dialog === "icons" ? "添加文件图标映射" : "添加在线浏览应用"} description="只添加当前页面的演示记录。" onSubmit={addMapping}>
      <AdminField label="文件扩展名"><input required value={extension} onChange={(event) => setExtension(event.target.value)} placeholder=".pdf" /></AdminField>
      <AdminField label="显示名称"><input required value={label} onChange={(event) => setLabel(event.target.value)} placeholder={dialog === "icons" ? "PDF 文件" : "PDF 预览器"} /></AdminField>
      {dialog === "apps" && <AdminField label="应用标识"><input required value={application} onChange={(event) => setApplication(event.target.value)} placeholder="viewer-pdf" /></AdminField>}
    </AdminFormDialog>
  </AdminPage>;
}

function MappingPanel({ kind, items, onAdd }: { kind: "icons" | "apps"; items: Mapping[]; onAdd: () => void }) {
  const icons = kind === "icons";
  return <AdminSection title={icons ? "文件图标" : "在线浏览应用"} description={icons ? "按扩展名配置文件类型图标。" : "管理文件类型与预览应用的匹配规则。"} actions={<Button variant="primary" onClick={onAdd}>添加演示映射</Button>}>
    <AdminStats><AdminStat label="映射规则" value={items.length} detail="当前页面临时记录" /><AdminStat label="已启用" value={items.filter((item) => item.status === "已启用").length} detail="仅用于演示" tone="info" /></AdminStats>
    <AdminTable items={items} getKey={(item) => item.id} columns={[
      { key: "extension", label: "扩展名", render: (item) => <code>{item.extension}</code> },
      { key: "label", label: "显示名称", render: (item) => item.label },
      { key: "application", label: icons ? "图标标识" : "应用标识", render: (item) => item.application || "默认" },
      { key: "status", label: "状态", render: (item) => item.status }
    ]} emptyTitle={icons ? "尚无图标映射" : "尚无浏览应用"} emptyMessage="可添加临时演示映射；刷新后恢复为空。" />
    <AdminPagination total={items.length} />
  </AdminSection>;
}
