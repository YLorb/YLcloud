import { AlertTriangle, Check, Eye, EyeOff, RefreshCw, Save, ServerCog } from "lucide-react";
import { useEffect, useMemo, useState, type ReactNode } from "react";
import { toast } from "sonner";
import { Button } from "../../../components/ui/Button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../../../components/ui/Tabs";
import { AdminConfirmDialog, AdminField } from "../components/AdminDialogs";
import { AdminPage, AdminSection } from "../components/AdminPage";
import { useAdminDataSource } from "../core/AdminDataSource";
import { LiveSettingsPage } from "./LiveSettingsPage";

type SettingValues = Record<string, string>;
const initialValues: SettingValues = {
  siteName: "", publicUrl: "", allowRegister: "true", sessionHours: "24",
  concurrentSession: "allow", riskChange: "revoke", captchaProvider: "disabled", captchaSiteKey: "", captchaSecret: "",
  paymentLabel: "", billingStatus: "disabled", smtpHost: "", smtpPort: "587", smtpEncryption: "starttls", smtpPassword: "",
  queueConcurrency: "4", queueRetry: "3", failureRetention: "30", serverName: "", maintenanceReason: ""
};

const tabs = [
  ["site", "站点信息"], ["session", "用户会话"], ["captcha", "验证码"], ["billing", "增值服务"],
  ["email", "邮件服务"], ["queue", "任务队列"], ["server", "服务器与维护"]
] as const;

export function SettingsPage() {
  return useAdminDataSource().mode === "live" ? <LiveSettingsPage /> : <MockSettingsPage />;
}

function MockSettingsPage() {
  const [values, setValues] = useState<SettingValues>(initialValues);
  const [saved, setSaved] = useState<SettingValues>(initialValues);
  const [showSecret, setShowSecret] = useState(false);
  const [maintenanceOpen, setMaintenanceOpen] = useState(false);
  const [resetOpen, setResetOpen] = useState(false);
  const dirty = useMemo(() => JSON.stringify(values) !== JSON.stringify(saved), [saved, values]);

  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => { if (dirty) event.preventDefault(); };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  function set(key: string, value: string) { setValues((current) => ({ ...current, [key]: value })); }
  function save() {
    const inRange = (key: string, min: number, max: number) => Number.isInteger(Number(values[key])) && Number(values[key]) >= min && Number(values[key]) <= max;
    if ((values.publicUrl && !/^https?:\/\/\S+$/i.test(values.publicUrl)) || !inRange("sessionHours", 1, 720) || !inRange("smtpPort", 1, 65535) || !inRange("queueConcurrency", 1, 128) || !inRange("queueRetry", 0, 20)) {
      toast.error("请检查访问地址及数值范围后再保存演示配置"); return;
    }
    setSaved(values); toast.success("演示配置已保存到当前页面内存，未写入服务器");
  }
  function reset() { setValues(saved); toast.message("已放弃尚未保存的演示修改"); }

  return (
    <AdminPage eyebrow="系统配置" title="参数设置" description="集中设计站点、会话、通知、队列和服务器设置。" actions={<><Button variant="ghost" disabled={!dirty} onClick={() => setResetOpen(true)}><RefreshCw size={16} />放弃修改</Button><Button variant="confirm" disabled={!dirty} onClick={save}>{dirty ? <Save size={16} /> : <Check size={16} />}{dirty ? "保存演示配置" : "无待保存修改"}</Button></>}>
      <Tabs defaultValue="site" className="admin-horizontal-tabs">
        <TabsList className="admin-tabs-list" aria-label="参数设置分类">{tabs.map(([key, label]) => <TabsTrigger key={key} value={key}>{label}</TabsTrigger>)}</TabsList>
        <div className="admin-settings-content">
          <TabsContent value="site"><SettingsSection title="站点信息" description="品牌名称、公开地址和注册入口。"><AdminField label="站点名称" hint="未连接服务器时保持为空。"><input value={values.siteName} onChange={(event) => set("siteName", event.target.value)} placeholder="YL Cloud" /></AdminField><AdminField label="公开访问地址"><input type="url" value={values.publicUrl} onChange={(event) => set("publicUrl", event.target.value)} placeholder="https://cloud.example.com" /></AdminField><AdminField label="允许注册"><select value={values.allowRegister} onChange={(event) => set("allowRegister", event.target.value)}><option value="true">开启</option><option value="false">关闭</option></select></AdminField></SettingsSection></TabsContent>
          <TabsContent value="session"><SettingsSection title="用户会话" description="会话时长、并发登录和安全退出策略。"><AdminField label="会话有效期（小时）"><input type="number" min="1" max="720" value={values.sessionHours} onChange={(event) => set("sessionHours", event.target.value)} /></AdminField><AdminField label="并发会话"><select value={values.concurrentSession} onChange={(event) => set("concurrentSession", event.target.value)}><option value="allow">允许多设备登录</option><option value="single">仅保留最近会话</option></select></AdminField><AdminField label="风险变更后"><select value={values.riskChange} onChange={(event) => set("riskChange", event.target.value)}><option value="revoke">撤销其他会话</option><option value="keep">保持现有会话</option></select></AdminField></SettingsSection></TabsContent>
          <TabsContent value="captcha"><SettingsSection title="验证码" description="登录和注册环节的人机验证入口。"><AdminField label="验证码服务"><select value={values.captchaProvider} onChange={(event) => set("captchaProvider", event.target.value)}><option value="disabled">暂不启用</option><option value="turnstile">Cloudflare Turnstile</option><option value="recaptcha">reCAPTCHA</option></select></AdminField><AdminField label="Site Key"><input value={values.captchaSiteKey} onChange={(event) => set("captchaSiteKey", event.target.value)} placeholder="演示字段，不要填入真实密钥" /></AdminField><SecretField label="Secret Key" value={values.captchaSecret} onChange={(value) => set("captchaSecret", value)} show={showSecret} onToggle={() => setShowSecret((value) => !value)} /></SettingsSection></TabsContent>
          <TabsContent value="billing"><SettingsSection title="增值服务" description="套餐展示、订单入口和计费提示文案。"><AdminField label="服务入口名称"><input value={values.paymentLabel} onChange={(event) => set("paymentLabel", event.target.value)} placeholder="升级存储空间" /></AdminField><AdminField label="计费状态"><select value={values.billingStatus} onChange={(event) => set("billingStatus", event.target.value)}><option value="disabled">暂不开放</option><option value="preview">仅展示套餐</option></select></AdminField><div className="admin-inline-warning"><AlertTriangle size={16} /><span>本阶段不连接支付渠道，任何选项都不会产生订单。</span></div></SettingsSection></TabsContent>
          <TabsContent value="email"><SettingsSection title="邮件服务" description="系统通知邮件的发件与连接参数。"><AdminField label="SMTP 主机"><input value={values.smtpHost} onChange={(event) => set("smtpHost", event.target.value)} placeholder="smtp.example.com" /></AdminField><AdminField label="SMTP 端口"><input type="number" min="1" max="65535" value={values.smtpPort} onChange={(event) => set("smtpPort", event.target.value)} /></AdminField><AdminField label="加密方式"><select value={values.smtpEncryption} onChange={(event) => set("smtpEncryption", event.target.value)}><option value="starttls">STARTTLS</option><option value="tls">TLS</option><option value="none">不加密（仅演示）</option></select></AdminField><SecretField label="SMTP 密码" value={values.smtpPassword} onChange={(value) => set("smtpPassword", value)} show={showSecret} onToggle={() => setShowSecret((value) => !value)} /></SettingsSection></TabsContent>
          <TabsContent value="queue"><SettingsSection title="任务队列" description="并发、重试和失败保留的前端配置结构。"><AdminField label="并发任务数"><input type="number" min="1" max="128" value={values.queueConcurrency} onChange={(event) => set("queueConcurrency", event.target.value)} /></AdminField><AdminField label="最大重试次数"><input type="number" min="0" max="20" value={values.queueRetry} onChange={(event) => set("queueRetry", event.target.value)} /></AdminField><AdminField label="失败记录保留"><select value={values.failureRetention} onChange={(event) => set("failureRetention", event.target.value)}><option value="7">7 天</option><option value="30">30 天</option><option value="90">90 天</option></select></AdminField></SettingsSection></TabsContent>
          <TabsContent value="server"><SettingsSection title="服务器与维护" description="服务标识、维护提示和受控维护入口。"><AdminField label="服务器名称"><input value={values.serverName} onChange={(event) => set("serverName", event.target.value)} placeholder="primary-node" /></AdminField><AdminField label="维护原因"><textarea rows={3} value={values.maintenanceReason} onChange={(event) => set("maintenanceReason", event.target.value)} placeholder="向用户说明维护影响和预计恢复时间" /></AdminField><AdminSection title="维护模式" description="进入维护模式会影响后续请求；当前按钮只演示确认流程。" actions={<Button variant="danger" onClick={() => setMaintenanceOpen(true)}><ServerCog size={16} />演示开启维护</Button>}><div className="admin-maintenance-status"><span>当前状态</span><strong>未连接服务器</strong><small>无法判断真实维护状态</small></div></AdminSection></SettingsSection></TabsContent>
        </div>
      </Tabs>
      <AdminConfirmDialog open={maintenanceOpen} onOpenChange={setMaintenanceOpen} title="开启演示维护状态？" description={`维护原因：${values.maintenanceReason || "未填写"}。确认后不会影响任何服务器请求。`} confirmLabel="确认演示" onConfirm={() => { setMaintenanceOpen(false); toast.success("已完成维护模式交互演示，服务器状态未改变"); }} />
      <AdminConfirmDialog open={resetOpen} onOpenChange={setResetOpen} title="放弃未保存的演示修改？" description="确认后表单恢复到上次保存在当前页面内存中的值。" confirmLabel="确认放弃" onConfirm={() => { reset(); setResetOpen(false); }} />
    </AdminPage>
  );
}

function SettingsSection({ title, description, children }: { title: string; description: string; children: ReactNode }) {
  return <section className="admin-settings-section"><header><h3>{title}</h3><p>{description}</p></header><div className="admin-settings-form">{children}</div></section>;
}

function SecretField({ label, value, onChange, show, onToggle }: { label: string; value: string; onChange: (value: string) => void; show: boolean; onToggle: () => void }) {
  return <AdminField label={label} hint="仅保存在当前页面内存，不要填写真实 Secret。"><div className="admin-secret-field"><input type={show ? "text" : "password"} value={value} onChange={(event) => onChange(event.target.value)} placeholder="仅输入演示值" /><button type="button" onClick={onToggle} aria-label={show ? `隐藏${label}` : `显示${label}`}>{show ? <EyeOff size={16} /> : <Eye size={16} />}</button></div></AdminField>;
}
