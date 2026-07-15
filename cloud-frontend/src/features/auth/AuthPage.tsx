import { FormEvent, useState } from "react";
import { BookOpenCheck, Files, HardDrive, Loader2, Network } from "lucide-react";
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
      <section className="auth-story" aria-label="产品介绍">
        <div className="auth-story-brand">
          <span className="brand-icon">
            <HardDrive size={22} />
          </span>
          <div>
            <strong>{siteName}</strong>
            <span>Knowledge asset cloud</span>
          </div>
        </div>

        <div className="auth-story-copy">
          <span className="auth-eyebrow">文件，从存储走向知识</span>
          <h1>让每一份资料，<br />都能被找到、协作与理解。</h1>
          <p>统一管理个人文件、团队空间与知识库，让资料沉淀自然进入检索和智能问答流程。</p>
        </div>

        <div className="auth-capability-list" aria-label="核心能力">
          <div><Files size={18} /><span><b>个人文件</b><small>上传、预览、分享与版本管理</small></span></div>
          <div><Network size={18} /><span><b>团队空间</b><small>成员协作与空间级文件治理</small></span></div>
          <div><BookOpenCheck size={18} /><span><b>知识工作台</b><small>文档画像、索引流水线与 RAG 问答</small></span></div>
        </div>
      </section>

      <section className="auth-card">
        <div className="auth-mobile-brand brand">
          <span className="brand-icon"><HardDrive size={22} /></span>
          <span>{siteName}</span>
        </div>

        <div className="auth-card-title">
          <span className="auth-form-kicker">{isSign ? "创建工作空间" : "欢迎回来"}</span>
          <h2>{isSign ? "创建账号" : `登录到 ${siteName}`}</h2>
          <p>{isSign ? "注册后将自动进入你的知识资产工作台。" : `继续管理 ${siteName} 中的文件与知识。`}</p>
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
