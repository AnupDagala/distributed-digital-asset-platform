import { readFile, writeFile } from 'node:fs/promises';

// Input is Maven's resolved runtime dependency tree; only public package coordinates leave this machine.
const file = process.argv[2] ?? 'target/dependency-tree.json';
const tree = JSON.parse(await readFile(file, 'utf8'));
const packages = new Map();
function visit(node) {
  if (node !== tree && ['compile', 'runtime'].includes(node.scope)) {
    const name = node.groupId + ':' + node.artifactId;
    packages.set(name + '@' + node.version, { package: { ecosystem: 'Maven', name }, version: node.version });
  }
  for (const child of node.children ?? []) visit(child);
}
visit(tree);
if (!packages.size) throw new Error('No runtime dependencies found; refusing an empty audit');
const findings = [];
for (let offset = 0; offset < packages.size; offset += 100) {
  let queries = [...packages.values()].slice(offset, offset + 100);
  while (queries.length) {
    const response = await fetch('https://api.osv.dev/v1/querybatch', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ queries }), signal: AbortSignal.timeout(60000),
    });
    if (!response.ok) throw new Error('OSV returned HTTP ' + response.status);
    const { results } = await response.json();
    if (!Array.isArray(results) || results.length !== queries.length) throw new Error('Incomplete OSV response');
    const next = [];
    results.forEach((result, index) => {
      for (const vulnerability of result.vulns ?? []) findings.push({
        package: queries[index].package.name, version: queries[index].version, id: vulnerability.id,
      });
      if (result.next_page_token) next.push({ ...queries[index], page_token: result.next_page_token });
    });
    queries = next;
  }
}
const report = { scannedAt: new Date().toISOString(), source: 'https://api.osv.dev/v1/querybatch',
  scope: 'Resolved Maven runtime dependencies; excludes container OS packages and build/test tooling',
  packagesScanned: packages.size, findings };
await writeFile('target/dependency-audit.json', JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({ packagesScanned: packages.size, findings: findings.length }));
for (const finding of findings) console.log(finding.package + '@' + finding.version + ': ' + finding.id);
if (findings.length) process.exitCode = 1;
