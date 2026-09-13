import { Check, Minus } from "lucide-react";
import { forwardRef, type InputHTMLAttributes } from "react";
import { cn } from "../../lib/cn";

type CheckboxProps = Omit<InputHTMLAttributes<HTMLInputElement>, "type"> & {
  indeterminate?: boolean;
};

export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(function Checkbox(
  { className, indeterminate, checked, ...props },
  ref
) {
  return (
    <span className={cn("ui-checkbox", className)}>
      <input ref={ref} type="checkbox" checked={checked} aria-checked={indeterminate ? "mixed" : checked} {...props} />
      <span aria-hidden="true">{indeterminate ? <Minus size={12} /> : checked ? <Check size={12} /> : null}</span>
    </span>
  );
});
