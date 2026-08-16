import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { ComponentBookPage } from "./ComponentBookPage";

describe("ComponentBookPage", () => {
  afterEach(() => { cleanup(); delete document.documentElement.dataset.theme; });

  it("renders the design system navigation and file status examples", () => {
    render(<ComponentBookPage />);

    expect(screen.getByRole("heading", { name: "产品研发资料库" })).toBeInTheDocument();
    expect(screen.getByRole("tablist", { name: "组件书页面示例" })).toBeInTheDocument();
    expect(screen.getByText("可检索")).toBeInTheDocument();
    expect(screen.getByText("处理中 45%")).toBeInTheDocument();
    expect(screen.getByText("处理失败")).toBeInTheDocument();
    expect(screen.getByText("等待处理")).toBeInTheDocument();
  });

  it("supports row selection and theme switching", async () => {
    const user = userEvent.setup();
    render(<ComponentBookPage />);

    await user.click(screen.getByRole("checkbox", { name: "选择 产品需求说明.pdf" }));
    expect(screen.getByText("已选择 1 项")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "使用深色主题" }));
    expect(document.documentElement.dataset.theme).toBe("dark");
  });
});
