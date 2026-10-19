import { readFileSync, writeFileSync } from 'node:fs';
import { stringify } from 'yaml';
import { buildOpenApi } from '../http/openapi.ts';
import { allRoutes } from '../modules/index.ts';

// Writes packages/contracts/openapi.yaml. CI runs with --check to fail when the
// committed contract is stale relative to the route definitions.
const target = new URL('../../../../packages/contracts/openapi.yaml', import.meta.url);
const pkg = JSON.parse(readFileSync(new URL('../../package.json', import.meta.url), 'utf8')) as { version: string };
const yaml = `# Generated from services/api route definitions. Do not edit by hand.\n${stringify(buildOpenApi(allRoutes(), pkg.version), { lineWidth: 0 })}`;

if (process.argv.includes('--check')) {
  let current = '';
  try {
    current = readFileSync(target, 'utf8');
  } catch {
    /* missing */
  }
  if (current !== yaml) {
    console.error('packages/contracts/openapi.yaml is out of date. Run: npm run generate:openapi');
    process.exit(1);
  }
  console.log('openapi.yaml is up to date');
} else {
  writeFileSync(target, yaml);
  console.log(`wrote ${target.pathname} (${allRoutes().length} operations)`);
}
