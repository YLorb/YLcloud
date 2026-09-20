import { useState, type FormEvent } from "react";
import { api, handleAuthenticationStatus } from "../../api";
import { Button } from "../../components/ui/Button";
import { ManagementSection } from "../../components/ui/ManagementPage";

export function LoginSecurityPanel() {
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);
  async function perform(action: () => Promise<unknown>) {
    setPending(true); setError("");
    try { await action(); handleAuthenticationStatus(401); }
    catch (cause) { setError(cause instanceof Error ? cause.message : "操作失败，请重试"); }
    finally { setPending(false); setCurrentPassword(""); setNewPassword(""); setConfirmation(""); }
  }
  function changePassword(event: FormEvent) {
    event.preventDefault();
    if (newPassword !== confirmation) { setError("两次输入的新密码不一致"); return; }
    void perform(() => api.changePassword(currentPassword, newPassword));
  }
  return <ManagementSection title="登录安全" description="记住登录最长 15 天；更改密码后所有设备需要重新登录。">
    <form className="dialog-form" onSubmit={changePassword}>
      <label>当前密码<input type="password" required maxLength={128} autoComplete="current-password" value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} /></label>
      <label>新密码<input type="password" required minLength={8} maxLength={72} autoComplete="new-password" value={newPassword} onChange={(event) => setNewPassword(event.target.value)} /></label>
      <label>确认新密码<input type="password" required minLength={8} maxLength={72} autoComplete="new-password" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} /></label>
      {error && <p role="alert">{error}</p>}
      <Button type="submit" loading={pending}>修改密码并退出所有设备</Button>
      <Button type="button" variant="danger" disabled={pending} onClick={() => {
        if (window.confirm("确定退出所有设备吗？当前设备也需要重新登录。")) void perform(api.logoutAll);
      }}>退出所有设备</Button>
    </form>
  </ManagementSection>;
}
