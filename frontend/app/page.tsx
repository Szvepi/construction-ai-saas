import Link from "next/link";

export default function HomePage() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center px-4">
      <h1 className="text-3xl font-bold text-brand-900">BuildAssist Email</h1>
      <p className="mt-2 max-w-md text-center text-slate-600">
        AI-powered email assistant for small construction companies.
      </p>
      <div className="mt-8 flex gap-4">
        <Link
          href="/auth/login"
          className="rounded-lg bg-brand-600 px-5 py-2.5 text-white hover:bg-brand-700"
        >
          Sign in
        </Link>
        <Link
          href="/auth/register"
          className="rounded-lg border border-slate-300 px-5 py-2.5 hover:bg-white"
        >
          Register
        </Link>
      </div>
    </main>
  );
}
