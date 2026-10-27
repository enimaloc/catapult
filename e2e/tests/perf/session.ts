import { expect, request, type APIRequestContext, type Page } from "@playwright/test";

/** The perf stack's test login and setup, shared by the specs of the perf project. */

export async function loginAs(page: Page, username: string) {
  const response = await page.goto(`/perf/login?username=${username}`);
  expect(response?.ok()).toBeTruthy();
}

/** A member signed in outside the browser (another device): posts as the site's own scripts do. */
export interface Member {
  api: APIRequestContext;
  /** Posts `data` as JSON with the XSRF header, and expects a success. */
  post(path: string, data?: object): Promise<void>;
  dispose(): Promise<void>;
}

/** Signs `username` in on a new request context, its XSRF cookie read from `page` (a page of the site). */
export async function signIn(username: string, page = "/"): Promise<Member> {
  const api = await request.newContext({ baseURL: process.env.PERF_BASE_URL });
  expect((await api.get(`/perf/login?username=${username}`)).ok()).toBeTruthy();
  expect((await api.get(page)).ok()).toBeTruthy();
  const xsrf = (await api.storageState()).cookies.find((c) => c.name === "XSRF-TOKEN")?.value ?? "";
  return {
    api,
    async post(path: string, data: object = {}) {
      const response = await api.post(path, { data, headers: { "X-XSRF-TOKEN": xsrf } });
      expect(response.ok(), `POST ${path}: ${response.status()}`).toBeTruthy();
    },
    dispose: () => api.dispose(),
  };
}

/**
 * Opens the custom bindings experiment to every member, as an admin would from the admin screen: it is
 * closed by default, which hides the API token card (and the /me/api-token request) from the channel page.
 */
export async function openCustomBindingsExperiment() {
  const admin = await signIn("perf-admin", "/admin");
  try {
    const { api, post } = admin;
    const list = await (await api.get("/spa/admin/experiments")).text();
    const row = list.split('data-admin-open="').find((chunk) => chunk.includes(">custom.bindings<"));
    const id = row?.slice(0, 36);
    expect(id, "custom.bindings experiment in the admin list").toBeTruthy();
    const detail = await (await api.get(`/spa/admin/experiments/${id}`)).text();
    // Already open (the worker restarts after a failed test and runs this again): activating again answers 409.
    if (/data-status="ACTIVE"/.test(detail)) return;
    const weights: Record<string, number> = {};
    for (const [, variant, key] of detail.matchAll(/data-variant="([0-9a-f-]{36})"[^>]*data-variant-key="([^"]+)"/g)) {
      weights[variant] = key === "control" ? 0 : 1;
    }
    expect(Object.keys(weights).length).toBeGreaterThan(1);
    await post(`/admin/experiments/${id}/weights`, weights);
    await post(`/admin/experiments/${id}/rules`, { type: "RANDOM", percentage: 100 });
    await post(`/admin/experiments/${id}/activate`);
  } finally {
    await admin.dispose();
  }
}
