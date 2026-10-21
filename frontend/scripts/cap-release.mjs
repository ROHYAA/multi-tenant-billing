import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { existsSync } from 'node:fs';

const frontend = join(dirname(fileURLToPath(import.meta.url)), '..');
const android = join(frontend, 'android');

function run(command, args, opts = {}) {
  const result = spawnSync(command, args, {
    cwd: opts.cwd ?? frontend,
    env: { ...process.env, ...opts.env },
    stdio: 'inherit',
    shell: true,
  });
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

run('node', ['scripts/assert-capacitor-prod.mjs']);
run('npx', ['ng', 'build', '--configuration=capacitor-prod']);
run('npx', ['cap', 'sync', 'android'], { env: { SHOPLEDGER_CAP_PROD: '1' } });

if (!existsSync(join(android, 'keystore.properties'))) {
  console.error('Missing android/keystore.properties (upload key). Generate it before assembling the release APK.');
  process.exit(1);
}

const gradlew = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
run(gradlew, ['assembleRelease'], { cwd: android, env: { SHOPLEDGER_CAP_PROD: '1' } });

console.log('Release APK: android/app/build/outputs/apk/release/app-release.apk');
