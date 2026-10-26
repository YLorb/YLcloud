import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import * as Tooltip from "@radix-ui/react-tooltip";
import { useState, type ReactNode } from "react";
import { Toaster } from "sonner";
import { SessionProvider } from "./session";
import { ApiError } from "../api";

export function AppProviders({ children }: { children: ReactNode }) {
  const [queryClient] = useState(() => new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 20_000,
        retry: (count, error) => !(error instanceof ApiError && (error.status === 429 || error.status === 503)) && count < 1,
        refetchOnWindowFocus: false
      },
      mutations: { retry: 0 }
    }
  }));

  return (
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <Tooltip.Provider delayDuration={250}>
          {children}
          <Toaster richColors closeButton position="top-right" />
        </Tooltip.Provider>
      </SessionProvider>
    </QueryClientProvider>
  );
}
