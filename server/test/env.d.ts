// The bindings tests see through `cloudflare:test`'s env.
declare namespace Cloudflare {
  interface Env {
    DB: D1Database;
    ACCOUNT: DurableObjectNamespace;
    GOOGLE_CLIENT_IDS: string;
    ENVIRONMENT: string;
  }
}
