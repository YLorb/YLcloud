import * as Tabs from "@radix-ui/react-tabs";
import { Lock } from "lucide-react";
import { AccessManagementPanel } from "../../settings/AccessManagementPanel";

const tabs = [
  { key: "site", label: "站点信息", available: true },
  { key: "session", label: "用户会话", available: false },
  { key: "captcha", label: "验证码", available: false },
  { key: "payment", label: "增值服务", available: false },
  { key: "email", label: "邮件服务", available: false },
  { key: "queue", label: "任务队列", available: false },
  { key: "events", label: "事件", available: false },
  { key: "server", label: "服务器设置", available: false }
];

export function SettingsPage() {
  return (
    <div className="admin-settings-page">
      <Tabs.Root defaultValue="site" className="admin-horizontal-tabs">
        <Tabs.List className="admin-tabs-list" aria-label="参数设置">
          {tabs.map(({ key, label, available }) => (
            <Tabs.Trigger key={key} value={key} disabled={!available} className={!available ? "admin-tab--unavailable" : undefined}>
              {label}
              {!available && <Lock size={12} />}
            </Tabs.Trigger>
          ))}
        </Tabs.List>
        <Tabs.Content value="site">
          <AccessManagementPanel />
        </Tabs.Content>
        {tabs.filter((t) => !t.available).map(({ key, label }) => (
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
