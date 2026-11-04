import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { SqliteStore } from "../../services/generator/src/adapters/sqlite-store.js";

test("one generator owns each data directory, and closing releases ownership", async () => {
  const dir = await mkdtemp(join(tmpdir(), "ge-owner-"));
  try {
    const first = new SqliteStore(dir);
    try {
      assert.throws(() => new SqliteStore(dir), /already in use/);
    } finally {
      first.close();
    }
    const next = new SqliteStore(dir);
    next.close();
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
