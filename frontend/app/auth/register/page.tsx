import Link from "next/link";
import { AuthForm } from "@/components/auth/AuthForm";

export default function RegisterPage() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center px-4">
      <h1 className="text-2xl font-bold">Create account</h1>
      <p className="mt-1 text-sm text-slate-600">
        Already registered?{" "}
        <Link href="/auth/login" className="text-brand-600 hover:underline">
          Sign in
        </Link>
      </p>
      <div className="mt-6 w-full max-w-sm">
        <AuthForm mode="register" />
      </div>
    </main>
  );
}
