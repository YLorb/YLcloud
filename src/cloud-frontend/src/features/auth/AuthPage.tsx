import { zodResolver } from "@hookform/resolvers/zod";
import { useQuery } from "@tanstack/react-query";
import { HardDrive, LockKeyhole, UserRound } from "lucide-react";
import { useForm } from "react-hook-form";
import { Link, Navigate, useLocation, useNavigate } from "react-router-dom";
import { z } from "zod";
import { api } from "../../api";
import { useSession } from "../../app/session";
import { Button } from "../../components/ui/Button";

const formSchema = z.object({
  nickname: z.string().trim().max(32, "昵称不能超过 32 个字符").optional(),
  username: z.string().trim().min(2, "用户名至少需要 2 个字符").max(64, "用户名过长"),
  password: z.string().min(6, "密码至少需要 6 个字符").max(128, "密码过长")
});

type AuthFields = z.infer<typeof formSchema>;

export function AuthPage({ mode }: { mode: "login" | "sign" }) {
  const { user, signIn } = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const isSign = mode === "sign";
  const settings = useQuery({ queryKey: ["public-settings"], queryFn: api.publicSettings, staleTime: 300_000, retry: 1 });
  const siteName = settings.data?.siteName || "YL Cloud";
  const allowRegister = settings.data?.allowRegister ?? true;
  const { register, handleSubmit, setError, formState: { errors, isSubmitting } } = useForm<AuthFields>({
    resolver: zodResolver(formSchema),
    defaultValues: { nickname: "", username: "", password: "" }
  });

  if (user) return <Navigate to="/files" replace />;

  async function submit(values: AuthFields) {
    if (isSign && !allowRegister) {
      setError("root", { message: "当前站点未开放注册" });
      return;
    }
    if (isSign && !values.nickname?.trim()) {
      setError("nickname", { message: "请输入昵称" });
      return;
    }
    try {
      if (isSign) {
        await api.sign({ username: values.username, password: values.password, nickname: values.nickname!.trim() });
      }
      const signedInUser = await api.login(values.username, values.password);
      signIn(signedInUser);
      const requestedPath = (location.state as { from?: string } | null)?.from;
      navigate(requestedPath || "/files", { replace: true });
    } catch (error) {
      setError("root", { message: error instanceof Error ? error.message : "认证失败，请稍后重试" });
    }
  }

  return (
    <main className="auth-page">
      <div className="auth-shell">
        <section className="auth-card" aria-labelledby="auth-title">
          <header className="auth-brand-row">
            <div className="auth-brand"><span className="brand-mark"><HardDrive size={20} /></span><strong>{siteName}</strong></div>
            <span className="auth-brand-meta">知识资产云</span>
          </header>

          <div className="auth-card-title">
            <h1 id="auth-title">{isSign ? "创建新账号" : "登录你的账号"}</h1>
            <p>{isSign ? "注册后即可上传资料并创建知识库。" : "继续管理文件、知识库与 AI 对话。"}</p>
          </div>

          <form className="auth-form" onSubmit={handleSubmit(submit)} noValidate>
            {isSign && <label className="auth-field">
              <span className="auth-field__label">昵称</span>
              <span className="auth-input-control"><UserRound size={18} aria-hidden="true" /><input {...register("nickname")} autoComplete="nickname" placeholder="例如：云盘管理员" aria-invalid={Boolean(errors.nickname)} /></span>
              {errors.nickname && <small className="field-error">{errors.nickname.message}</small>}
            </label>}
            <label className="auth-field">
              <span className="auth-field__label">用户名</span>
              <span className="auth-input-control"><UserRound size={18} aria-hidden="true" /><input {...register("username")} autoComplete="username" placeholder="请输入用户名" aria-invalid={Boolean(errors.username)} /></span>
              {errors.username && <small className="field-error">{errors.username.message}</small>}
            </label>
            <label className="auth-field">
              <span className="auth-field__label">密码</span>
              <span className="auth-input-control"><LockKeyhole size={18} aria-hidden="true" /><input {...register("password")} type="password" autoComplete={isSign ? "new-password" : "current-password"} placeholder="至少 6 个字符" aria-invalid={Boolean(errors.password)} /></span>
              {errors.password && <small className="field-error">{errors.password.message}</small>}
            </label>
            {errors.root && <div className="error-banner" role="alert">{errors.root.message}</div>}
            <Button className="full-width auth-submit" variant="primary" size="lg" type="submit" loading={isSubmitting} disabled={isSign && !allowRegister}>
              {isSign && !allowRegister ? "注册已关闭" : isSign ? "注册" : "登录"}
            </Button>
          </form>

          <p className="auth-switch">{isSign ? "已有账号？" : "还没有账号？"} <Link to={isSign ? "/login" : "/sign"} aria-disabled={!isSign && !allowRegister}>{isSign ? "立即登录" : "立即注册"}</Link></p>
          <footer className="auth-legal">使用即表示你同意服务条款与隐私政策</footer>
        </section>
        <p className="auth-powered">Powered by <span className="brand-mark"><HardDrive size={12} /></span> {siteName}</p>
      </div>
    </main>
  );
}
