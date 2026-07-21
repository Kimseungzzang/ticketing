const AUTH_BASE = process.env.NEXT_PUBLIC_AUTH_BASE_API_URL ?? 'http://localhost:8090';

let refreshPromise: Promise<string | null> | null = null;

function clearAuth() {
  localStorage.removeItem('accessToken');
  localStorage.removeItem('refreshToken');
  localStorage.removeItem('tokenType');
  localStorage.removeItem('user');
}

async function requestNewAccessToken(): Promise<string | null> {
  const storedRefreshToken = localStorage.getItem('refreshToken');
  if (!storedRefreshToken) return null;

  const res = await fetch(`${AUTH_BASE}/api/auth/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: storedRefreshToken }),
  });
  if (!res.ok) return null;

  const data = await res.json() as { accessToken: string; refreshToken: string };
  localStorage.setItem('accessToken', data.accessToken);
  localStorage.setItem('refreshToken', data.refreshToken);
  return data.accessToken;
}

// 동시에 여러 요청이 401을 만나도 refresh는 한 번만 실행되도록 진행 중인 promise를 공유.
function refreshAccessTokenOnce(): Promise<string | null> {
  if (!refreshPromise) {
    refreshPromise = requestNewAccessToken().finally(() => { refreshPromise = null; });
  }
  return refreshPromise;
}

// accessToken이 만료(401)되면 refreshToken으로 자동 갱신 후 원래 요청을 한 번 재시도한다.
// 갱신까지 실패하면(refreshToken도 만료/무효) 로그인 페이지로 보낸다.
export async function authFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const buildInit = (token: string | null): RequestInit => ({
    ...init,
    headers: {
      ...(init.headers ?? {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  });

  const accessToken = localStorage.getItem('accessToken');
  let res = await fetch(input, buildInit(accessToken));

  if (res.status === 401) {
    const newToken = await refreshAccessTokenOnce();
    if (!newToken) {
      clearAuth();
      window.location.href = '/';
      throw new Error('세션이 만료되었습니다. 다시 로그인해 주세요.');
    }
    res = await fetch(input, buildInit(newToken));
  }

  return res;
}
