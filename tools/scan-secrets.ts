// Scans tracked files for credentials that must never be committed (CLAUDE.md: no production
// secrets in apps, source, logs, skills or prompts). High-signal patterns only, so a finding is
// worth stopping for. A line that must contain a look-alike (a test vector, a documented
// placeholder) carries the marker `secret-scan: allow`.
//
//   node tools/scan-secrets.ts            scan tracked files; exit 1 on any finding
import { execFileSync } from 'node:child_process';
import { readFileSync, statSync } from 'node:fs';

interface Rule {
  id: string;
  pattern: RegExp;
  /** Extra check on the match, e.g. decode a JWT and look at its claims. */
  confirm?: (match: string) => boolean;
}

export const RULES: Rule[] = [
  { id: 'private-key', pattern: /-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----/ },
  { id: 'aws-access-key', pattern: /\b(?:AKIA|ASIA)[0-9A-Z]{16}\b/ },
  { id: 'google-api-key', pattern: /\bAIza[0-9A-Za-z_-]{35}\b/ },
  { id: 'github-token', pattern: /\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{22,})\b/ },
  { id: 'slack-token', pattern: /\bxox[abposr]-[A-Za-z0-9-]{10,}\b/ },
  { id: 'stripe-live-key', pattern: /\b[rs]k_live_[0-9A-Za-z]{20,}\b/ },
  { id: 'anthropic-key', pattern: /\bsk-ant-[A-Za-z0-9_-]{20,}\b/ },
  { id: 'openai-key', pattern: /\bsk-(?:proj-)?[A-Za-z0-9_-]{40,}\b/ },
  { id: 'revenuecat-secret-key', pattern: /\bsk_[A-Za-z0-9]{24,}\b/ },
  { id: 'firebase-server-key', pattern: /\bAAAA[A-Za-z0-9_-]{7}:[A-Za-z0-9_-]{140}\b/ },
  {
    // Supabase's service-role key is a JWT; anon keys are publishable and allowed.
    id: 'service-role-jwt',
    pattern: /\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}/,
    confirm: (jwt) => {
      try {
        const claims = JSON.parse(Buffer.from(jwt.split('.')[1]!, 'base64url').toString('utf8')) as { role?: unknown };
        return claims.role === 'service_role';
      } catch {
        return false;
      }
    },
  },
];

export interface Finding {
  file: string;
  line: number;
  rule: string;
  excerpt: string;
}

/** Findings in one file's text. Values are masked in the excerpt so the report never repeats a secret. */
export function scanText(file: string, text: string): Finding[] {
  const findings: Finding[] = [];
  text.split('\n').forEach((line, i) => {
    if (line.includes('secret-scan: allow')) return;
    for (const rule of RULES) {
      const m = rule.pattern.exec(line);
      if (!m || (rule.confirm && !rule.confirm(m[0]))) continue;
      findings.push({ file, line: i + 1, rule: rule.id, excerpt: m[0].slice(0, 6) + '…' });
    }
  });
  return findings;
}

function trackedFiles(): string[] {
  return execFileSync('git', ['ls-files', '-z'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 }).split('\0').filter(Boolean);
}

function main(): void {
  const findings: Finding[] = [];
  let scanned = 0;
  for (const file of trackedFiles()) {
    let size = 0;
    try {
      size = statSync(file).size;
    } catch {
      continue; // deleted in the working tree
    }
    if (size > 2 * 1024 * 1024) continue;
    const buf = readFileSync(file);
    if (buf.subarray(0, 8000).includes(0)) continue; // binary
    scanned++;
    findings.push(...scanText(file, buf.toString('utf8')));
  }
  for (const f of findings) console.error(`${f.file}:${f.line}: ${f.rule} (${f.excerpt})`);
  console.log(`${scanned} files scanned, ${findings.length} finding(s)`);
  if (findings.length) process.exit(1);
}

if (import.meta.main) main();
