import { useCallback, useRef, useState } from "react";

export function useAdminWriteLock() {
  const locksRef = useRef(new Set<string>());
  const [lockedKeys, setLockedKeys] = useState<ReadonlySet<string>>(() => new Set());

  const runLocked = useCallback(async <T,>(key: string, operation: () => Promise<T>) => {
    if (locksRef.current.has(key)) {
      return undefined;
    }
    locksRef.current.add(key);
    setLockedKeys(new Set(locksRef.current));
    try {
      return await operation();
    } finally {
      locksRef.current.delete(key);
      setLockedKeys(new Set(locksRef.current));
    }
  }, []);

  const isLocked = useCallback((key: string) => lockedKeys.has(key), [lockedKeys]);

  return { runLocked, isLocked };
}
