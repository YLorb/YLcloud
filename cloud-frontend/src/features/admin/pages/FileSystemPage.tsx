import * as Tabs from "@radix-ui/react-tabs";
import { Lock } from "lucide-react";

const tabs = [
  { key: "params", label: "参数设置", available: false },
  { key: "search", label: "全文搜索", available: false },
  { key: "icons", label: "文件图标", available: false },
  { key: "preview", label: "文件在线浏览应用", available: false }
];

export function FileSystemPage() {
  return (
    <div className="admin-filesystem-page">
      <Tabs.Root defaultValue="params" className="admin-horizontal-tabs">
        <Tabs.List className="admin-tabs-list" aria-label="文件系统">
          {tabs.map(({ key, label }) => (
            <Tabs.Trigger key={key} value={key} className="admin-tab--unavailable">
              {label}
              <Lock size={12} />
            </Tabs.Trigger>
          ))}
        </Tabs.List>
        {tabs.map(({ key, label }) => (
          <Tabs.Content key={key} value={key}>
            <div className="admin-page-placeholder">
              <Lock size={36} />
              <strong>{label}</strong>
              <p>功能尚未开放，敬请期待</p>
            </div>
          </Tabs.Content>
        ))}
      </Tabs.Root>
    </div>
  );
}
