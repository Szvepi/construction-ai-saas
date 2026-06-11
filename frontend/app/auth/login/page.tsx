import Link from "next/link";
import { AuthForm } from "@/components/auth/AuthForm";

export default function LoginPage() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center px-4">
      <h1 className="text-2xl font-bold">Sign in</h1>
      <p className="mt-1 text-sm text-slate-600">
        No account?{" "}
        <Link href="/auth/register" className="text-brand-600 hover:underline">
          Register
        </Link>
      </p>
      <div className="mt-6 w-full max-w-sm">
        <AuthForm mode="login" />
      </div>
    </main>
  );
}
