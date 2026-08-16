import { forwardRef, type InputHTMLAttributes, type ReactNode } from "react";
import { cn } from "../../lib/cn";

type InputProps = InputHTMLAttributes<HTMLInputElement> & {
  leading?: ReactNode;
  trailing?: ReactNode;
  invalid?: boolean;
};

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { className, leading, trailing, invalid, ...props },
  ref
) {
  return (
    <label className={cn("ui-input", invalid && "ui-input--error", className)}>
      {leading && <span className="ui-input__icon" aria-hidden="true">{leading}</span>}
      <input ref={ref} aria-invalid={invalid || undefined} {...props} />
      {trailing && <span className="ui-input__trailing">{trailing}</span>}
    </label>
  );
});
