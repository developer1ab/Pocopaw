const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

function fail(message) {
  process.stderr.write(`${message}\n`);
  process.exit(1);
}

function runProcess(command, args, options = {}) {
  const result = spawnSync(command, args, {
    stdio: ['pipe', 'pipe', 'pipe'],
    maxBuffer: 10 * 1024 * 1024,
    ...options,
  });
  const stdout = bufferToString(result.stdout);
  const stderr = bufferToString(result.stderr);
  if (result.error) {
    fail(`${command} failed: ${result.error.message}`);
  }
  if (result.status !== 0) {
    fail(`${command} ${args.join(' ')} failed: ${stderr || stdout || `exit ${result.status}`}`);
  }
  return { stdout, stderr };
}

function bufferToString(value) {
  if (!value) {
    return '';
  }
  return Buffer.isBuffer(value) ? value.toString('utf8') : String(value);
}

function parseArgs(argv) {
  const args = {
    appId: 'com.atombits.pocopaw',
    backupDir: path.resolve(__dirname, '..', 'logs', 'store-backups'),
    restart: false,
  };

  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    switch (token) {
      case '--device':
        args.device = argv[++index];
        break;
      case '--app-id':
        args.appId = argv[++index];
        break;
      case '--backup-dir':
        args.backupDir = path.resolve(argv[++index]);
        break;
      case '--restart':
        args.restart = true;
        break;
      default:
        fail(`Unknown argument: ${token}`);
    }
  }

  return args;
}

function resolveDevice(explicitDevice) {
  if (explicitDevice) {
    return explicitDevice;
  }
  const { stdout } = runProcess('adb', ['devices']);
  const devices = stdout
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('List of devices attached'))
    .map((line) => line.split(/\s+/))
    .filter((parts) => parts[1] === 'device')
    .map((parts) => parts[0]);
  if (devices.length === 1) {
    return devices[0];
  }
  if (devices.length === 0) {
    fail('No connected adb device found.');
  }
  fail(`Multiple devices found. Re-run with --device. Devices: ${devices.join(', ')}`);
}

function adbArgs(device, args) {
  return device ? ['-s', device, ...args] : args;
}

function readStore(device, appId) {
  return runProcess('adb', adbArgs(device, ['exec-out', 'run-as', appId, 'cat', 'files/prototype_store.json'])).stdout;
}

function writeStore(device, appId, storeJson) {
  const storePath = `/data/user/0/${appId}/files/prototype_store.json`;
  runProcess(
    'adb',
    adbArgs(device, ['shell', `run-as ${appId} sh -c 'cat > ${storePath}'`]),
    { input: Buffer.from(storeJson, 'utf8') }
  );
}

function forceStop(device, appId) {
  runProcess('adb', adbArgs(device, ['shell', 'am', 'force-stop', appId]));
}

function launchMainActivity(device, appId) {
  runProcess('adb', adbArgs(device, ['shell', 'am', 'start', '-n', `${appId}/.MainActivity`]));
}

function buildCounts(store) {
  const memoryState = store.memoryState || {};
  return {
    readyProcessAssets: listCount(store.readyProcessAssets),
    processAssetEntries: listCount(store.processAssetEntries),
    processExtractionRawMaterials: listCount(store.processExtractionRawMaterials),
    processShortcutAtlas: listCount(store.processShortcutAtlas),
    pageEvidenceAssets: listCount(store.pageEvidenceAssets),
    processAssetEvents: listCount(store.processAssetEvents),
    processExtractionConsumedIds: listCount(store.processExtractionConsumedIds),
    processCandidateStore: listCount(memoryState.processCandidateStore),
    processFeedbackStore: listCount(memoryState.processFeedbackStore),
  };
}

function listCount(value) {
  return Array.isArray(value) ? value.length : 0;
}

function clearProcessState(store) {
  const nextStore = { ...store };
  nextStore.processExtractionRawMaterials = [];
  nextStore.readyProcessAssets = [];
  nextStore.processAssetEntries = [];
  nextStore.pageEvidenceAssets = [];
  nextStore.processShortcutAtlas = [];
  nextStore.processAssetEvents = [];
  nextStore.processExtractionConsumedIds = [];
  nextStore.lastProcessCurationSummary = null;
  nextStore.latestCompletedProcessReviewContext = null;
  nextStore.pendingProcessRecoveryContext = null;
  nextStore.currentProcessReuseContext = null;
  nextStore.currentProcessRuntime = null;

  nextStore.currentState = {
    ...(nextStore.currentState || {}),
    pendingProcessFeedbackDraft: null,
  };

  if (nextStore.memoryState) {
    nextStore.memoryState = {
      ...nextStore.memoryState,
      processCandidateStore: [],
      processFeedbackStore: [],
    };
  }

  return nextStore;
}

function ensureDir(dirPath) {
  fs.mkdirSync(dirPath, { recursive: true });
}

function backupStore(backupDir, device, rawStore) {
  ensureDir(backupDir);
  const timestamp = new Date().toISOString().replace(/[:T]/g, '-').replace(/\.\d+Z$/, 'Z');
  const backupPath = path.join(backupDir, `prototype_store_before_process_clear_${device}_${timestamp}.json`);
  fs.writeFileSync(backupPath, rawStore, 'utf8');
  return backupPath;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const device = resolveDevice(args.device);

  forceStop(device, args.appId);
  const rawStore = readStore(device, args.appId);
  const backupPath = backupStore(args.backupDir, device, rawStore);
  const parsedStore = JSON.parse(rawStore);
  const beforeCounts = buildCounts(parsedStore);
  const clearedStore = clearProcessState(parsedStore);
  const afterCounts = buildCounts(clearedStore);

  writeStore(device, args.appId, `${JSON.stringify(clearedStore, null, 2)}\n`);
  const verifiedStore = JSON.parse(readStore(device, args.appId));
  const verifiedCounts = buildCounts(verifiedStore);

  if (args.restart) {
    launchMainActivity(device, args.appId);
  }

  process.stdout.write(`${JSON.stringify({
    device,
    backupPath,
    beforeCounts,
    afterCounts,
    verifiedCounts,
    restarted: args.restart,
  }, null, 2)}\n`);
}

main();