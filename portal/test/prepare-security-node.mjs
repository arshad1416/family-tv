// Only touches disposable tsc output. No downloads, package installation or production DB access.
import { readdir, readFile, writeFile, symlink, lstat } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
const output = resolve(process.argv[2] ?? '');
const portal = resolve(fileURLToPath(new URL('..', import.meta.url)));
if (!process.argv[2] || output === portal || output.startsWith(portal + '/')) throw new Error('Use a disposable output directory outside portal');
async function rewrite(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) await rewrite(path);
    else if (entry.name.endsWith('.js')) {
      const text = await readFile(path, 'utf8');
      await writeFile(path, text.replace(/(from\s+["'])(\.{1,2}\/[^"']+)(["'])/g, (_, a, path, b) => a + path + (path.endsWith('.js') ? '' : '.js') + b));
    }
  }
}
await rewrite(output);
await writeFile(join(output, 'package.json'), '{"type":"module"}\n');
const migrations = join(output, 'migrations');
if (!await lstat(migrations).catch(() => null)) await symlink(join(portal, 'migrations'), migrations, 'dir');
