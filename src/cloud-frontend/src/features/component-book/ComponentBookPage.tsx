import {
  Bell, BookOpen, Bot, CheckCircle2, ChevronDown, ChevronLeft, CircleHelp, Clock3,
  Database, FileText, Files, Folder, KeyRound, ListFilter, ListTodo, Moon, MoreHorizontal,
  Search, Settings, ShieldCheck, Sparkles, Sun, Upload, UserRound, Users
} from "lucide-react";
import { useEffect, useState } from "react";
import { Button } from "../../components/ui/Button";
import { Checkbox } from "../../components/ui/Checkbox";
import { Input } from "../../components/ui/Input";
import {
  Sidebar, SidebarBody, SidebarFooter, SidebarHeader, SidebarItem, SidebarSection, SidebarSectionLabel
} from "../../components/ui/Sidebar";
import { StatusBadge } from "../../components/ui/StatusBadge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../../components/ui/Tabs";

const rows = [
  { name: "产品需求说明.pdf", type: "PDF", status: "可检索", tone: "success" as const, size: "2.45 MB", time: "6 分钟前" },
  { name: "技术架构设计.docx", type: "DOCX", status: "处理中 45%", tone: "running" as const, size: "1.78 MB", time: "8 分钟前" },
  { name: "生产部署手册.pdf", type: "PDF", status: "处理失败", tone: "danger" as const, size: "3.21 MB", time: "15 分钟前" },
  { name: "API 接口规范.md", type: "MD", status: "等待处理", tone: "neutral" as const, size: "1.12 MB", time: "20 分钟前" }
];

export function ComponentBookPage() {
  const [dark, setDark] = useState(false);
  const [selected, setSelected] = useState(new Set<number>());

  useEffect(() => {
    document.documentElement.dataset.theme = dark ? "dark" : "light";
    return () => { delete document.documentElement.dataset.theme; };
  }, [dark]);

  const toggle = (index: number) => setSelected((current) => {
    const next = new Set(current);
    next.has(index) ? next.delete(index) : next.add(index);
    return next;
  });

  return (
    <div className="component-book-shell">
      <Sidebar className="component-book-sidebar" aria-label="组件书导航示例">
        <SidebarHeader>
          <div className="component-book-brand"><span><BookOpen size={18} /></span><div><strong>YL Cloud</strong><small>个人空间</small></div><ChevronDown size={16} /></div>
        </SidebarHeader>
        <SidebarBody>
          <SidebarSection>
            <SidebarSectionLabel>工作区</SidebarSectionLabel>
            <SidebarItem><Files size={18} /><span>文件</span></SidebarItem>
            <SidebarItem><Database size={18} /><span>知识库</span></SidebarItem>
            <SidebarItem><Bot size={18} /><span>AI 问答</span></SidebarItem>
            <SidebarItem badge="3"><ListTodo size={18} /><span>任务</span></SidebarItem>
          </SidebarSection>
          <SidebarSection>
            <SidebarSectionLabel>当前知识库</SidebarSectionLabel>
            <SidebarItem level={2}><Folder size={18} /><span>产品研发资料库</span></SidebarItem>
            <SidebarItem level={3} selected><FileText size={18} /><span>文件</span></SidebarItem>
            <SidebarItem level={3}><Clock3 size={18} /><span>索引状态</span></SidebarItem>
            <SidebarItem level={3}><Sparkles size={18} /><span>知识画像</span></SidebarItem>
            <SidebarItem level={3}><Users size={18} /><span>成员与权限</span></SidebarItem>
          </SidebarSection>
          <SidebarSection>
            <SidebarSectionLabel>最近</SidebarSectionLabel>
            <SidebarItem level={2}><span>客户支持知识库</span></SidebarItem>
            <SidebarItem level={2}><span>API 接口规范</span></SidebarItem>
          </SidebarSection>
        </SidebarBody>
        <SidebarFooter>
          <div className="component-book-quota"><span>存储空间</span><strong>18 GB / 50 GB</strong><progress value={36} max={100} /></div>
          <SidebarItem><Settings size={18} /><span>设置</span></SidebarItem>
          <SidebarItem><UserRound size={18} /><span>张三</span></SidebarItem>
          <SidebarItem><ChevronLeft size={18} /><span>收起导航</span></SidebarItem>
        </SidebarFooter>
      </Sidebar>

      <section className="component-book-workspace">
        <header className="component-book-topbar">
          <nav aria-label="面包屑"><span>知识库</span><span>/</span><span>产品研发资料库</span><span>/</span><strong>文件</strong></nav>
          <Input className="component-book-global-search" leading={<Search size={17} />} placeholder="搜索文件、知识库或对话" />
          <div><Button variant="ghost" size="icon" aria-label="帮助"><CircleHelp size={18} /></Button><Button variant="ghost" size="icon" aria-label="通知"><Bell size={18} /></Button><Button variant="ghost" size="icon" aria-label={dark ? "使用浅色主题" : "使用深色主题"} onClick={() => setDark((value) => !value)}>{dark ? <Sun size={18} /> : <Moon size={18} />}</Button><span className="component-book-avatar">张</span></div>
        </header>

        <main className="component-book-main">
          <div className="component-book-heading"><div><h1>产品研发资料库</h1><p>团队知识库 · 42 个文件 · 更新于 6 分钟前</p></div><div><Button variant="primary"><Upload size={17} />上传文件</Button><Button variant="ghost" size="icon" aria-label="更多操作"><MoreHorizontal size={18} /></Button></div></div>

          <Tabs defaultValue="files">
            <TabsList aria-label="组件书页面示例">
              <TabsTrigger value="files">文件</TabsTrigger>
              <TabsTrigger value="components">组件</TabsTrigger>
              <TabsTrigger value="tokens">Design Tokens</TabsTrigger>
            </TabsList>
            <TabsContent value="files">
              <div className="component-book-toolbar">
                <Input leading={<Search size={17} />} placeholder="搜索当前知识库" />
                <Button variant="outline"><ListFilter size={17} />文件类型<ChevronDown size={15} /></Button>
                <Button variant="outline">索引状态<ChevronDown size={15} /></Button>
              </div>
              {selected.size > 0 && <div className="component-book-bulk"><strong>已选择 {selected.size} 项</strong><div><Button size="sm">下载</Button><Button size="sm">移动</Button><Button size="sm">复制</Button><Button size="sm">添加到知识库</Button><Button variant="danger" size="sm">删除</Button></div></div>}
              <div className="component-book-table-wrap">
                <table className="component-book-table">
                  <thead><tr><th><Checkbox checked={selected.size === rows.length} indeterminate={selected.size > 0 && selected.size < rows.length} onChange={() => setSelected(selected.size === rows.length ? new Set() : new Set(rows.map((_, index) => index)))} aria-label="选择全部文件" /></th><th>名称</th><th>索引状态</th><th>大小</th><th>更新时间</th><th><span className="sr-only">操作</span></th></tr></thead>
                  <tbody>{rows.map((row, index) => <tr key={row.name} className={selected.has(index) ? "is-selected" : undefined}><td><Checkbox checked={selected.has(index)} onChange={() => toggle(index)} aria-label={`选择 ${row.name}`} /></td><td><span className="component-book-file"><FileText size={18} /><span><strong>{row.name}</strong><small>{row.type}</small></span></span></td><td><StatusBadge tone={row.tone}>{row.status}</StatusBadge></td><td>{row.size}</td><td>{row.time}</td><td><Button variant="ghost" size="icon" aria-label={`${row.name} 的更多操作`}><MoreHorizontal size={17} /></Button></td></tr>)}</tbody>
                </table>
              </div>
            </TabsContent>
            <TabsContent value="components"><ComponentSamples /></TabsContent>
            <TabsContent value="tokens"><TokenSamples /></TabsContent>
          </Tabs>
        </main>
        <button className="component-book-ai" aria-label="打开 AI 问答"><Bot size={21} /><span>AI</span></button>
      </section>
    </div>
  );
}

function ComponentSamples() {
  return <div className="component-book-samples">
    <section><h2>Buttons</h2><div><Button variant="primary">Primary</Button><Button>Secondary</Button><Button variant="outline">Outline</Button><Button variant="ghost">Ghost</Button><Button variant="danger">Danger</Button><Button loading>Loading</Button></div></section>
    <section><h2>Status</h2><div><StatusBadge tone="success">可检索</StatusBadge><StatusBadge tone="running">处理中</StatusBadge><StatusBadge tone="warning">需要关注</StatusBadge><StatusBadge tone="danger">失败</StatusBadge><StatusBadge>等待处理</StatusBadge></div></section>
    <section><h2>Inputs</h2><div><Input leading={<Search size={16} />} placeholder="默认输入框" /><Input invalid leading={<ShieldCheck size={16} />} defaultValue="错误状态" /><Button variant="primary"><KeyRound size={16} />保存设置</Button></div></section>
  </div>;
}

function TokenSamples() {
  return <div className="component-book-token-grid">
    {["background-page", "background-surface", "background-hover", "background-selected-neutral", "background-selected-accent", "action-primary", "action-secondary", "status-success", "status-warning", "status-error"].map((token) => <article key={token}><span style={{ background: `var(--${token})` }} /><code>--{token}</code></article>)}
  </div>;
}
