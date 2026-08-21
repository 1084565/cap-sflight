#!/usr/bin/env node
'use strict';

// Checkmarx One SAST/SCA scan integration for SAP CI&D Compliance stage.
// Requires: Node.js (available in CI&D Compliance stage container).
// No npm dependencies.

const https = require('https');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execFile } = require('child_process');

// --- Configuration from environment ---

const REQUIRED_VARS = ['CLIENT_ID', 'CX_CLIENT_SECRET', 'CX_APPLICATION', 'CX_PROJECT', 'CX_TENANT', 'CX_URI'];

function getEnv() {
  const missing = REQUIRED_VARS.filter(v => !process.env[v]);
  if (missing.length > 0) {
    console.error(`Missing required environment variables: ${missing.join(', ')}`);
    process.exit(1);
  }
  return {
    clientId:     process.env.CLIENT_ID,
    clientSecret: process.env.CX_CLIENT_SECRET,
    application:  process.env.CX_APPLICATION,
    project:      process.env.CX_PROJECT,
    tenant:     process.env.CX_TENANT,
    baseUri:    process.env.CX_URI,
    authUri:    process.env.CX_AUTH_URI || process.env.CX_URI,
    branch:     process.env.GIT_BRANCH || 'main',
    // Threshold format: "sast-high=0;sast-medium=10;sca-high=0"
    threshold:  process.env.CX_THRESHOLD || '',
    cliVersion: process.env.CX_CLI_VERSION || '2.3.63',
    scanTypes:  process.env.CX_SCAN_TYPES || '',
  };
}

// --- CLI download ---

function download(url, dest) {
  return new Promise((resolve, reject) => {
    const file = fs.createWriteStream(dest);
    https.get(url, res => {
      if (res.statusCode === 301 || res.statusCode === 302) {
        file.close();
        fs.unlinkSync(dest);
        return download(res.headers.location, dest).then(resolve).catch(reject);
      }
      if (res.statusCode !== 200) {
        file.close();
        fs.unlinkSync(dest);
        return reject(new Error(`HTTP ${res.statusCode} downloading ${url}`));
      }
      res.pipe(file);
      file.on('finish', () => file.close(resolve));
    }).on('error', err => {
      file.close();
      try { fs.unlinkSync(dest); } catch (_) {}
      reject(err);
    });
  });
}

function downloadText(url) {
  return new Promise((resolve, reject) => {
    https.get(url, res => {
      if (res.statusCode === 301 || res.statusCode === 302) {
        return downloadText(res.headers.location).then(resolve).catch(reject);
      }
      if (res.statusCode !== 200) {
        return reject(new Error(`HTTP ${res.statusCode} downloading ${url}`));
      }
      let data = '';
      res.on('data', chunk => { data += chunk; });
      res.on('end', () => resolve(data));
    }).on('error', reject);
  });
}

function sha256File(filePath) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha256');
    const stream = fs.createReadStream(filePath);
    stream.on('data', chunk => hash.update(chunk));
    stream.on('end', () => resolve(hash.digest('hex')));
    stream.on('error', reject);
  });
}

async function downloadCli(version, destDir) {
  const tarName = `ast-cli_${version}_linux_x64.tar.gz`;
  const tarPath = path.join(destDir, tarName);
  const checksumUrl = `https://github.com/Checkmarx/ast-cli/releases/download/${version}/ast-cli_${version}_checksums.txt`;
  const tarUrl = `https://github.com/Checkmarx/ast-cli/releases/download/${version}/${tarName}`;

  console.log(`Downloading Checkmarx One CLI ${version}...`);
  await download(tarUrl, tarPath);

  console.log('Verifying checksum...');
  const checksums = await downloadText(checksumUrl);
  const expectedLine = checksums.split('\n').find(l => l.includes(tarName));
  if (!expectedLine) {
    throw new Error(`Checksum entry for ${tarName} not found in checksums.txt`);
  }
  const expected = expectedLine.trim().split(/\s+/)[0];
  const actual = await sha256File(tarPath);
  if (actual !== expected) {
    throw new Error(`Checksum mismatch for ${tarName}: expected ${expected}, got ${actual}`);
  }
  console.log('Checksum OK.');

  // Extract
  await new Promise((resolve, reject) => {
    execFile('tar', ['-xzf', tarPath, '-C', destDir], (err) => {
      if (err) reject(err); else resolve();
    });
  });
  fs.unlinkSync(tarPath);

  const cxBin = path.join(destDir, 'cx');
  fs.chmodSync(cxBin, 0o755);
  return cxBin;
}

async function printScanHelp(cxBin) {
  return new Promise(resolve => {
    execFile(cxBin, ['scan', 'create', '--help'], { maxBuffer: 1 * 1024 * 1024 }, (err, stdout, stderr) => {
      console.log('=== cx scan create --help ===');
      if (stdout) process.stdout.write(stdout);
      if (stderr) process.stderr.write(stderr);
      console.log('=== end help ===');
      resolve(); // never fail the build on help
    });
  });
}

// --- Scan ---

function runScan(cxBin, env) {
  return new Promise((resolve, reject) => {
    const args = [
      'scan', 'create',
      '--project-name',    env.project,
      '--application-name', env.application,
      '--file-source',     process.cwd(),
      '--client-id',       env.clientId,
      '--base-uri',        env.baseUri,
      '--base-auth-uri',   env.authUri,
      '--tenant',          env.tenant,
      '--branch',          env.branch,
    ];

    if (env.threshold) {
      args.push('--threshold', env.threshold);
    }

    if (env.scanTypes) {
      args.push('--scan-types', env.scanTypes);
    }

    // CLIENT_SECRET passed as env var only - never in args (would appear in ps/audit logs)
    const scanEnv = Object.assign({}, process.env, {
      CX_CLIENT_SECRET: env.clientSecret,
    });

    console.log(`Starting Checkmarx One scan for project "${env.project}" on branch "${env.branch}"...`);

    const child = execFile(cxBin, args, { env: scanEnv, maxBuffer: 10 * 1024 * 1024 }, (err, stdout, stderr) => {
      if (stdout) process.stdout.write(stdout);
      if (stderr) process.stderr.write(stderr);
      if (err) reject(err); else resolve();
    });
  });
}

// --- Main ---

async function main() {
  const env = getEnv();
  const destDir = path.join(process.cwd(), '.cx-cli');
  fs.mkdirSync(destDir, { recursive: true });

  let cxBin;
  try {
    cxBin = await downloadCli(env.cliVersion, destDir);
  } catch (err) {
    console.error(`CLI download/verification failed: ${err.message}`);
    process.exit(1);
  }

  await printScanHelp(cxBin);

  try {
    await runScan(cxBin, env);
  } catch (err) {
    console.error(`Scan failed: ${err.message}`);
    process.exit(1);
  }
}

main();
