import { loadSigningKey } from '@wakeme/challenges';
import { loadConfig } from '../config.ts';
import { PgDb } from '../db/db.ts';
import { resignPublishedRevisions } from '../modules/challenges/repo.ts';

// Key rotation step 3 (docs/runbooks/key-rotation.md): re-sign every published
// revision with the current CHALLENGE_SIGNING_KEY. Safe to re-run.
const config = loadConfig();
const db = new PgDb(config.databaseUrl, 1);
try {
  const r = await resignPublishedRevisions(db, loadSigningKey(config.challengeSigning.privateKeyPem), config.challengeSigning.keyId);
  console.log(`re-signed ${r.resigned} revision(s) with key ${config.challengeSigning.keyId}`);
  if (r.mismatched.length) {
    console.error(`digest mismatch, left unchanged: ${r.mismatched.join(', ')}`);
    process.exitCode = 2;
  }
} finally {
  await db.close();
}
