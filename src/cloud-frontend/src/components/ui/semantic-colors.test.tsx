import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Button } from "./Button";
import { StatusBadge } from "./StatusBadge";

describe("semantic action and status styling", () => {
  it("marks the main confirmation action with the confirm semantic class", () => {
    render(<Button variant="confirm">确认上传</Button>);
    expect(screen.getByRole("button", { name: "确认上传" })).toHaveClass("ui-button--confirm");
  });

  it("marks dangerous actions and system failures with danger semantics", () => {
    render(<><Button variant="danger">永久删除</Button><StatusBadge tone="danger">系统错误</StatusBadge></>);
    expect(screen.getByRole("button", { name: "永久删除" })).toHaveClass("ui-button--danger");
    expect(screen.getByText("系统错误")).toHaveClass("status-badge--danger");
  });

  it("does not rely on color alone for status", () => {
    const { container } = render(<StatusBadge tone="success">处理成功</StatusBadge>);
    expect(screen.getByText("处理成功")).toBeVisible();
    expect(container.querySelector("svg")).toBeInTheDocument();
  });
});
