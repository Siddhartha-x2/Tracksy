import { useCallback, useEffect, useRef, useState } from 'react';
import { api, errorMessage } from '../api';
export function useResource<T>(path: string | null, revision = 0) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(Boolean(path));
  const [localRevision, setLocalRevision] = useState(0);
  const lastPath = useRef(path);
  const reload = useCallback(() => setLocalRevision(value => value + 1), []);
  useEffect(() => {
    if (!path) { setLoading(false); setData(null); return; }
    if (lastPath.current !== path) { setData(null); lastPath.current = path; }
    const controller = new AbortController();
    setLoading(true); setError('');
    api<T>(path, { signal: controller.signal }).then(value => { if (!controller.signal.aborted) setData(value); }).catch(err => { if (!controller.signal.aborted) setError(errorMessage(err)); }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [path, revision, localRevision]);
  return { data, error, loading, reload };
}
