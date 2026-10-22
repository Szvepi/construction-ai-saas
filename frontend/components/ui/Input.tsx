import { InputHTMLAttributes } from "react";

type Props = InputHTMLAttributes<HTMLInputElement> & {
  label: string;
};

export function Input({ label, id, className = "", ...props }: Props) {
  const inputId = id ?? props.name;
  return (
    <label className="block text-sm">
      <span className="font-medium text-slate-700">{label}</span>
      <input
        id={inputId}
        className={`mt-1 w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-slate-900 shadow-sm placeholder:text-slate-400 focus:border-brand-500 focus:outline-none focus:ring-2 focus:ring-brand-500/20 disabled:cursor-not-allowed disabled:bg-slate-50 ${className}`}
        {...props}
      />
    </label>
  );
}
