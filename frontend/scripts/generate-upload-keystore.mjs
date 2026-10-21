import { randomBytes } from 'node:crypto';
import { writeFileSync, existsSync, unlinkSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const android = join(dirname(fileURLToPath(import.meta.url)), '..', 'android');
const storeFile = 'shopledger-upload.jks';
const jks = join(android, storeFile);
const props = join(android, 'keystore.properties');

if (existsSync(jks) || existsSync(props)) {
  console.error('Upload key already exists. Back it up; do not regenerate if you already shipped an APK.');
  process.exit(1);
}

const password = randomBytes(24).toString('base64url');
const passFile = join(android, '.keypass.tmp');
writeFileSync(passFile, password, { encoding: 'utf8', mode: 0o600 });

const result = spawnSync(
  'keytool',
  [
    '-genkeypair',
    '-alias',
    'shopledger',
    '-keyalg',
    'RSA',
    '-keysize',
    '2048',
    '-validity',
    '10000',
    '-keystore',
    jks,
    '-dname',
    'CN=ShopLedger, OU=ShopLedger, O=ShopLedger, L=Unknown, ST=Unknown, C=IN',
    '-storepass:file',
    passFile,
    '-keypass:file',
    passFile,
  ],
  { stdio: 'inherit' },
);

try {
  unlinkSync(passFile);
} catch {
  /* ignore */
}

if (result.status !== 0) {
  process.exit(result.status ?? 1);
}

writeFileSync(
  props,
  `storeFile=${storeFile}\nstorePassword=${password}\nkeyAlias=shopledger\nkeyPassword=${password}\n`,
  { encoding: 'utf8', mode: 0o600 },
);

console.log('Created android/shopledger-upload.jks and android/keystore.properties (gitignored). Copy both somewhere safe.');
