// Server-only identity-provider administration (account deletion). The
// service-role key is used by the worker process only, never by request
// handlers that serve user traffic, and never shipped to clients.

export interface AuthAdmin {
  deleteUser(authSubject: string): Promise<void>;
}

export class SupabaseAuthAdmin implements AuthAdmin {
  private readonly url: string;
  private readonly key: string;
  private readonly fetchImpl: typeof fetch;

  constructor(url: string, serviceRoleKey: string, fetchImpl: typeof fetch = fetch) {
    this.url = url.replace(/\/$/, '');
    this.key = serviceRoleKey;
    this.fetchImpl = fetchImpl;
  }

  async deleteUser(authSubject: string): Promise<void> {
    if (!/^[0-9a-f-]{36}$/i.test(authSubject)) throw new Error('unexpected auth subject format');
    const res = await this.fetchImpl(`${this.url}/auth/v1/admin/users/${authSubject}`, {
      method: 'DELETE',
      headers: { apikey: this.key, authorization: `Bearer ${this.key}` },
      signal: AbortSignal.timeout(10_000),
    });
    if (!res.ok && res.status !== 404) throw new Error(`auth_admin_delete_${res.status}`);
  }
}

export class RecordingAuthAdmin implements AuthAdmin {
  readonly deleted: string[] = [];
  async deleteUser(authSubject: string): Promise<void> {
    this.deleted.push(authSubject);
  }
}
