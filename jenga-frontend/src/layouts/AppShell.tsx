import { Suspense } from "react";
import { Outlet } from "react-router-dom";
import { Sidebar } from "./Sidebar";
import { BottomNav } from "./BottomNav";
import { Wordmark } from "../components/Wordmark";
import { OfflineBanner } from "../components/OfflineBanner";
import { LoadingRows } from "../components/ui/Skeleton";
import { useAuth } from "../hooks/useAuth";

export function AppShell() {
  const { logout } = useAuth();
  return (
    <div className="flex min-h-dvh">
      <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-50 focus:bg-surface focus:px-4 focus:py-2">
        Skip to content
      </a>
      <Sidebar />
      <div className="flex min-w-0 flex-1 flex-col">
        <OfflineBanner />
        <header className="flex items-center justify-between border-b border-rule px-5 py-3 md:hidden">
          <Wordmark />
          <button onClick={() => logout("manual")} className="text-sm text-muted underline-offset-4 hover:underline">
            Sign out
          </button>
        </header>
        <main id="main" className="mx-auto w-full max-w-5xl flex-1 px-5 pb-28 pt-8 md:px-10 md:pb-16 md:pt-12">
          <Suspense fallback={<LoadingRows />}>
            <Outlet />
          </Suspense>
        </main>
      </div>
      <BottomNav />
    </div>
  );
}
