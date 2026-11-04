CREATE TABLE IF NOT EXISTS definitions(definition_id TEXT NOT NULL,version INTEGER NOT NULL,task_id TEXT NOT NULL UNIQUE,host_id TEXT NOT NULL,ref_json TEXT NOT NULL,view_json TEXT NOT NULL,bundle_path TEXT NOT NULL,provenance_json TEXT NOT NULL,PRIMARY KEY(definition_id,version));
CREATE TABLE IF NOT EXISTS generation_attempts(task_id TEXT NOT NULL,attempt_no INTEGER NOT NULL,source_hash TEXT,usage_json TEXT,model TEXT,report_json TEXT,created_at TEXT NOT NULL,PRIMARY KEY(task_id,attempt_no));
CREATE TABLE IF NOT EXISTS delivery_events(event_id TEXT PRIMARY KEY,host_id TEXT NOT NULL,task_id TEXT NOT NULL,delivery_id TEXT NOT NULL,sequence_no INTEGER NOT NULL,state TEXT NOT NULL,event_hash TEXT NOT NULL,event_json TEXT NOT NULL,UNIQUE(host_id,delivery_id,sequence_no));
CREATE TABLE IF NOT EXISTS observations(event_id TEXT PRIMARY KEY,host_id TEXT NOT NULL,definition_id TEXT NOT NULL,version INTEGER NOT NULL,event_hash TEXT NOT NULL,event_json TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS observations_by_definition ON observations(host_id,definition_id,version,event_id);
INSERT OR IGNORE INTO schema_migrations VALUES(2);
