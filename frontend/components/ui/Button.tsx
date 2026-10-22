"use client";

import { ButtonHTMLAttributes } from "react";

type Props = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "primary" | "secondary" | "ghost";
};

const variants = {
  primary: "bg-brand-600 text-white shadow-sm shadow-brand-900/10 hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-50",
  secondary: "border border-slate-200 bg-white text-slate-700 shadow-sm hover:border-slate-300 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50",
  ghost: "text-slate-600 hover:bg-slate-100 hover:text-slate-900 disabled:cursor-not-allowed disabled:opacity-50",
};

export function Button({
  variant = "primary",
  className = "",
  children,
  ...props
}: Props) {
  return (
    <button
      className={`inline-flex items-center justify-center rounded-lg px-4 py-2 text-sm font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2 ${variants[variant]} ${className}`}
      {...props}
    >
      {children}
    </button>
  );
}
