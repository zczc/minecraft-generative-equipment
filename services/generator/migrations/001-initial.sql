CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY);
CREATE TABLE IF NOT EXISTS capability_profiles(profile_id TEXT PRIMARY KEY,host_id TEXT NOT NULL,profile_hash TEXT NOT NULL,profile_json TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS generation_tasks(task_id TEXT PRIMARY KEY,host_id TEXT NOT NULL,input_hash TEXT NOT NULL,input_json TEXT NOT NULL,resolved_json TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('QUEUED','GENERATING','VALIDATING','REPAIRING','READY','FAILED','CANCELLED')),revision INTEGER NOT NULL,created_at TEXT NOT NULL,error_json TEXT);
CREATE INDEX IF NOT EXISTS generation_task_status ON generation_tasks(status);
INSERT OR IGNORE INTO schema_migrations VALUES(1);
