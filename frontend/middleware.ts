import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

export function middleware(req: NextRequest) {
  const url = req.nextUrl.clone();
  const pathname = url.pathname;

  // Allow Next internals, API, static files and the OAuth callback to pass through
  if (
    pathname.startsWith("/_next") ||
    pathname.startsWith("/api") ||
    pathname.startsWith("/static") ||
    pathname === "/favicon.ico" ||
    // Allow all auth routes (login, oauth-callback, register, etc.) to pass through
    pathname.startsWith("/auth")
  ) {
    return NextResponse.next();
  }

  // Simple auth check: look for a cookie named "token". Adjust if your app stores auth elsewhere.
  const token = req.cookies.get("buildassist_token")?.value;
  if (!token) {
    // Redirect unauthenticated users to the login page
    url.pathname = "/auth/login";
    url.search = `from=${encodeURIComponent(pathname)}`;
    return NextResponse.redirect(url);
  }

  return NextResponse.next();
}

// Apply middleware to all routes (adjust matcher if you want to limit scope)
export const config = {
  matcher: "/:path*",
};
