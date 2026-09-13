import { isRouteErrorResponse, useNavigate, useRouteError } from "react-router-dom";
import { Button } from "../components/ui/Button";

export function RouteError() {
  const error = useRouteError();
  const navigate = useNavigate();
  const message = isRouteErrorResponse(error)
    ? `${error.status} ${error.statusText}`
    : error instanceof Error ? error.message : "页面发生未知错误";
  return (
    <main className="route-error" role="alert">
      <span className="route-error__code">SYSTEM ERROR</span>
      <h1>页面暂时无法显示</h1>
      <p>{message}</p>
      <div className="button-row">
        <Button variant="danger" onClick={() => window.location.reload()}>重新加载</Button>
        <Button onClick={() => navigate("/files")}>返回我的文件</Button>
      </div>
    </main>
  );
}
