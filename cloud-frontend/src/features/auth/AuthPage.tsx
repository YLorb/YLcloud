import { zodResolver } from "@hookform/resolvers/zod";
import { useQuery } from "@tanstack/react-query";
import { BookOpenCheck, Files, HardDrive, Network } from "lucide-react";
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
      <section className="auth-story" aria-label="产品介绍">
        <div className="auth-story-brand">
          <span className="brand-mark"><HardDrive size={22} /></span>
          <div><strong>{siteName}</strong><span>知识资产云</span></div>
        </div>
        <div className="auth-story-copy">
          <span className="auth-eyebrow">文件，从存储走向知识</span>
          <h1>上传文档，自动构建<br />可追溯知识库。</h1>
          <p>资料上传后自动完成解析和索引；每个回答都能定位到原文，让查找、验证和协作更简单。</p>
        </div>
        <div className="auth-capability-list" aria-label="核心能力">
          <div><Files size={19} /><span><b>个人文件</b><small>上传、预览、分享与版本管理</small></span></div>
          <div><Network size={19} /><span><b>团队空间</b><small>成员协作与空间级文件治理</small></span></div>
          <div><BookOpenCheck size={19} /><span><b>知识工作台</b><small>索引流水线、知识画像与可信问答</small></span></div>
        </div>
      </section>

      <section className="auth-card">
        <div className="auth-mobile-brand"><span className="brand-mark"><HardDrive size={21} /></span><strong>{siteName}</strong></div>
        <header className="auth-card-title">
          <span className="auth-form-kicker">{isSign ? "创建账号" : "欢迎回来"}</span>
          <h2>{isSign ? "创建账号" : `登录到 ${siteName}`}</h2>
          <p>{isSign ? "注册后即可上传资料并创建知识库。" : "继续管理资料，或基于知识库开始提问。"}</p>
        </header>
        <form className="form-stack" onSubmit={handleSubmit(submit)} noValidate>
          {isSign && <label className="field"><span>昵称</span><input {...register("nickname")} autoComplete="nickname" placeholder="例如：云盘管理员" aria-invalid={Boolean(errors.nickname)} />{errors.nickname && <small className="field-error">{errors.nickname.message}</small>}</label>}
          <label className="field"><span>用户名</span><input {...register("username")} autoComplete="username" placeholder="请输入用户名" aria-invalid={Boolean(errors.username)} />{errors.username && <small className="field-error">{errors.username.message}</small>}</label>
          <label className="field"><span>密码</span><input {...register("password")} type="password" autoComplete={isSign ? "new-password" : "current-password"} placeholder="至少 6 个字符" aria-invalid={Boolean(errors.password)} />{errors.password && <small className="field-error">{errors.password.message}</small>}</label>
          {errors.root && <div className="error-banner" role="alert">{errors.root.message}</div>}
          <Button className="full-width" variant="primary" size="lg" type="submit" loading={isSubmitting} disabled={isSign && !allowRegister}>
            {isSign && !allowRegister ? "注册已关闭" : isSign ? "注册并登录" : "进入工作台"}
          </Button>
        </form>
        <p className="auth-switch">{isSign ? "已有账号？" : "还没有账号？"} <Link to={isSign ? "/login" : "/sign"} aria-disabled={!isSign && !allowRegister}>{isSign ? "返回登录" : "立即注册"}</Link></p>
      </section>
    </main>
  );
}
