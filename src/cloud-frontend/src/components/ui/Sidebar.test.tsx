import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { SidebarItem } from "./Sidebar";

describe("SidebarItem", () => {
  it("slots a navigation link without requiring a badge child", () => {
    render(
      <SidebarItem asChild selected>
        <a href="/files"><span>文件</span></a>
      </SidebarItem>
    );

    const link = screen.getByRole("link", { name: "文件" });
    expect(link).toHaveClass("ui-sidebar-item", "ui-sidebar-item--selected");
    expect(link).toHaveAttribute("aria-current", "page");
  });

  it("keeps an optional badge inside the slotted navigation element", () => {
    render(
      <SidebarItem asChild badge="3">
        <a href="/tasks"><span>任务</span></a>
      </SidebarItem>
    );

    const link = screen.getByRole("link", { name: "任务 3" });
    expect(link).toContainElement(screen.getByText("3"));
  });
});
