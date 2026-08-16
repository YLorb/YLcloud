import { Slot } from "@radix-ui/react-slot";
import { forwardRef, type ButtonHTMLAttributes, type HTMLAttributes, type ReactNode } from "react";
import { cn } from "../../lib/cn";

export function Sidebar({ className, ...props }: HTMLAttributes<HTMLElement>) {
  return <aside className={cn("ui-sidebar", className)} {...props} />;
}

export function SidebarHeader({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("ui-sidebar__header", className)} {...props} />;
}

export function SidebarBody({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("ui-sidebar__body", className)} {...props} />;
}

export function SidebarFooter({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("ui-sidebar__footer", className)} {...props} />;
}

export function SidebarSection({ className, ...props }: HTMLAttributes<HTMLElement>) {
  return <section className={cn("ui-sidebar-section", className)} {...props} />;
}

export function SidebarSectionLabel({ className, ...props }: HTMLAttributes<HTMLSpanElement>) {
  return <span className={cn("ui-sidebar-section__label", className)} {...props} />;
}

type SidebarItemProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  asChild?: boolean;
  selected?: boolean;
  level?: 1 | 2 | 3;
  badge?: ReactNode;
};

export const SidebarItem = forwardRef<HTMLButtonElement, SidebarItemProps>(function SidebarItem(
  { asChild, selected, level = 1, badge, className, children, ...props },
  ref
) {
  const Comp = asChild ? Slot : "button";
  return (
    <Comp
      ref={ref}
      className={cn("ui-sidebar-item", `ui-sidebar-item--level-${level}`, selected && "ui-sidebar-item--selected", className)}
      aria-current={selected ? "page" : undefined}
      {...props}
    >
      {children}
      {badge != null && <span className="ui-sidebar-item__badge">{badge}</span>}
    </Comp>
  );
});
