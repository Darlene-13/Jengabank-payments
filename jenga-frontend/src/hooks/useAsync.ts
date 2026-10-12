import { useCallback, useEffect, useRef, useState, type DependencyList } from "react";
import { ApiError, toApiError } from "../api/client";
import type { Page } from "../types/api";

interface AsyncState<T> {
  data: T | null;
  error: ApiError | null;
  loading: boolean;
}

export function useAsync<T>(fn: (signal: AbortSignal) => Promise<T>, deps: DependencyList) {
  const [state, setState] = useState<AsyncState<T>>({ data: null, error: null, loading: true });
  const [tick, setTick] = useState(0);
  const fnRef = useRef(fn);
  fnRef.current = fn;

  useEffect(() => {
    const ctrl = new AbortController();
    setState((s) => ({ ...s, loading: true, error: null }));
    fnRef
      .current(ctrl.signal)
      .then((data) => !ctrl.signal.aborted && setState({ data, error: null, loading: false }))
      .catch((e) => !ctrl.signal.aborted && setState({ data: null, error: toApiError(e), loading: false }));
    return () => ctrl.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  const setData = useCallback((data: T) => setState({ data, error: null, loading: false }), []);
  return { ...state, reload, setData };
}

export function usePaged<T>(
  fetchPage: (cursor: string | undefined, signal: AbortSignal) => Promise<Page<T>>,
  deps: DependencyList,
) {
  const [items, setItems] = useState<T[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [tick, setTick] = useState(0);
  const fetchRef = useRef(fetchPage);
  fetchRef.current = fetchPage;

  useEffect(() => {
    const ctrl = new AbortController();
    setLoading(true);
    setError(null);
    fetchRef
      .current(undefined, ctrl.signal)
      .then((p) => {
        if (ctrl.signal.aborted) return;
        setItems(p.items);
        setCursor(p.nextCursor);
        setLoading(false);
      })
      .catch((e) => {
        if (ctrl.signal.aborted) return;
        setError(toApiError(e));
        setLoading(false);
      });
    return () => ctrl.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  const loadMore = useCallback(async () => {
    if (!cursor || loadingMore) return;
    setLoadingMore(true);
    try {
      const p = await fetchRef.current(cursor, new AbortController().signal);
      setItems((prev) => [...prev, ...p.items]);
      setCursor(p.nextCursor);
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setLoadingMore(false);
    }
  }, [cursor, loadingMore]);

  const reload = useCallback(() => setTick((t) => t + 1), []);

  return { items, loading, loadingMore, error, hasMore: !!cursor, loadMore, reload, setItems };
}
