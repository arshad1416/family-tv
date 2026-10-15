// A small D1 stand-in on Node's built-in SQLite, with the real migrations applied.
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { createRequire } from "node:module";
import type { DatabaseSync as Db } from "node:sqlite";
import { fileURLToPath } from "node:url";

// Loaded with require: Vite doesn't know node:sqlite as a built-in and fails to resolve the import.
const { DatabaseSync } = createRequire(import.meta.url)("node:sqlite") as typeof import("node:sqlite");

class Stmt {
  constructor(private db: Db, private sql: string, private args: unknown[] = []) {}
  bind(...args: unknown[]) {
    return new Stmt(this.db, this.sql, args);
  }
  private params() {
    return this.args.map((a) => (a === undefined ? null : typeof a === "boolean" ? Number(a) : a)) as (string | number | null)[];
  }
  async first<T>() {
    return (this.db.prepare(this.sql).get(...this.params()) ?? null) as T | null;
  }
  async all<T>() {
    return { results: this.db.prepare(this.sql).all(...this.params()) as T[] };
  }
  async run() {
    this.db.prepare(this.sql).run(...this.params());
    return { success: true };
  }
}

export function memoryD1(): D1Database {
  const db = new DatabaseSync(":memory:");
  db.exec("PRAGMA foreign_keys = ON");
  const dir = join(fileURLToPath(import.meta.url), "../../migrations");
  for (const f of readdirSync(dir).sort()) db.exec(readFileSync(join(dir, f), "utf8"));
  return {
    prepare: (sql: string) => new Stmt(db, sql),
    batch: async (stmts: Stmt[]) => Promise.all(stmts.map((s) => s.run())),
  } as unknown as D1Database;
}
