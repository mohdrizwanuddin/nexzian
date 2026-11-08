const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, "") ??
  "http://localhost:8080/api/v1";

let csrfTokenRequest: Promise<void> | undefined;
let refreshRequest: Promise<boolean> | undefined;
const SESSION_EXPIRED_EVENT = "nexzian:session-expired";

export class ApiError extends Error {
  public readonly status: number;

  constructor(
    status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }

  get isUnauthorized() {
    return this.status === 401;
  }

  get isForbidden() {
    return this.status === 403;
  }
}

const messageForStatus = (status: number) => {
  if (status === 401) return "Your session has expired. Please sign in again.";
  if (status === 403) return "You do not have permission to perform this action.";
  return `Request failed with status ${status}.`;
};

const errorMessage = async (response: Response) => {
  const body = await response.text();
  if (!body) return messageForStatus(response.status);

  try {
    const data: unknown = JSON.parse(body);
    if (typeof data === "object" && data !== null && "message" in data) {
      const { message } = data as { message?: unknown };
      if (typeof message === "string" && message.trim()) return message;
    }
  } catch {
    // Plain-text error responses are also supported.
  }

  return body;
};

const getCookie = (name: string) =>
  document.cookie
    .split("; ")
    .find((cookie) => cookie.startsWith(`${name}=`))
    ?.slice(name.length + 1);

const requiresCsrfToken = (method = "GET") =>
  !["GET", "HEAD", "OPTIONS"].includes(method.toUpperCase());

const ensureCsrfToken = async () => {
  if (getCookie("XSRF-TOKEN")) return;

  csrfTokenRequest ??= fetch(`${API_BASE_URL}/auth/csrf`, {
    credentials: "include",
  }).then((response) => {
    if (!response.ok) {
      throw new ApiError(response.status, "Unable to establish CSRF protection.");
    }
  }).finally(() => {
    csrfTokenRequest = undefined;
  });

  await csrfTokenRequest;
};

const addCsrfHeader = (headers: Headers, method?: string) => {
  if (!requiresCsrfToken(method)) return;

  const csrfToken = getCookie("XSRF-TOKEN");
  if (csrfToken) headers.set("X-XSRF-TOKEN", decodeURIComponent(csrfToken));
};

const refreshAccessToken = async () => {
  refreshRequest ??= (async () => {
    await ensureCsrfToken();
    const headers = new Headers();
    addCsrfHeader(headers, "POST");
    const response = await fetch(`${API_BASE_URL}/auth/refresh`, {
      method: "POST",
      credentials: "include",
      headers,
    });
    return response.ok;
  })().finally(() => {
    refreshRequest = undefined;
  });

  return refreshRequest;
};

const notifySessionExpired = () => {
  window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT));
};

export const onSessionExpired = (handler: () => void) => {
  window.addEventListener(SESSION_EXPIRED_EVENT, handler);
  return () => window.removeEventListener(SESSION_EXPIRED_EVENT, handler);
};

const request = (path: string, options: RequestInit, headers: Headers) =>
  fetch(`${API_BASE_URL}${path}`, { ...options, credentials: "include", headers });

export async function apiRequest<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  if (requiresCsrfToken(options.method)) await ensureCsrfToken();
  const headers = new Headers(options.headers);
  addCsrfHeader(headers, options.method);

  if (options.body && !(options.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  let response = await request(path, options, headers);
  if (response.status === 401 && !path.startsWith("/auth/")) {
    if (await refreshAccessToken()) {
      response = await request(path, options, headers);
    } else {
      notifySessionExpired();
    }
  }
  if (!response.ok) throw new ApiError(response.status, await errorMessage(response));
  if (response.status === 204) return null as T;

  return response.json() as Promise<T>;
}

export async function apiBlobRequest(path: string, options: RequestInit = {}) {
  if (requiresCsrfToken(options.method)) await ensureCsrfToken();
  const headers = new Headers(options.headers);
  addCsrfHeader(headers, options.method);

  let response = await request(path, options, headers);
  if (response.status === 401 && !path.startsWith("/auth/")) {
    if (await refreshAccessToken()) {
      response = await request(path, options, headers);
    } else {
      notifySessionExpired();
    }
  }
  if (!response.ok) throw new ApiError(response.status, await errorMessage(response));
  return response.blob();
}
