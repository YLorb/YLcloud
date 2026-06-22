import { FormEvent, useState } from "react";
import { HardDrive, Loader2 } from "lucide-react";
import { api, setSession } from "../../api";
import type { AuthMode } from "../../appTypes";
import type { PublicSiteSettings, User } from "../../types";

export function AuthPage({
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
