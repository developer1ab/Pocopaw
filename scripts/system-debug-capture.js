#!/usr/bin/env node

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const { commandExists, ensureDir } = require('./lib/utils');

const DEFAULTS = Object.freeze({
  appId: 'com.atombits.pocopaw',
  outputDir: path.join('logs', 'captures'),
  minutes: 5,
  jdPackage: 'com.jingdong.app.mall',
  includeUiDump: true,
  includeScreenshot: true
});

const FOCUS_PATTERNS = Object.freeze([
  'topResumedActivity',
  'mResumedActivity',
  'mCurrentFocus',
  'mFocusedApp',
  'ResumedActivity',
  'CurrentFocus'
]);

const LOGCAT_KEYWORDS = Object.freeze([
  'com.atombits.pocopaw',
  'com.jingdong.app.mall',
  'prototype',
  'MainActivity',
  'CaptureService',
  'PrototypeAccessibilityService',
  'ActivityTaskManager',
  'ActivityManager',
  'WindowManager',
  'AndroidRuntime',
  'START u0',
  'cmp=com.atombits.pocopaw',
  'cmp=com.jingdong.app.mall',
  'run-as',
  'uiautomator'
]);

function writeLine(message) {
  process.stdout.write(`${message}\n`);
}

function fail(message) {
  process.stderr.write(`${message}\n`);
  process.exit(1);
}

function parseArgs(argv) {
  const args = argv.slice(2);
  let options = { ...DEFAULTS, device: null };

  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index];
    switch (arg) {
      case '--device':
        options = { ...options, device: args[index + 1] || null };
        index += 1;
        break;
      case '--app-id':
        options = { ...options, appId: args[index + 1] || '' };
        index += 1;
        break;
      case '--output-dir':
        options = { ...options, outputDir: args[index + 1] || '' };
        index += 1;
        break;
      case '--minutes':
        options = { ...options, minutes: Number.parseInt(args[index + 1] || '', 10) };
        index += 1;
        break;
      case '--jd-package':
        options = { ...options, jdPackage: args[index + 1] || '' };
        index += 1;
        break;
      case '--no-ui-dump':
        options = { ...options, includeUiDump: false };
        break;
      case '--no-screenshot':
        options = { ...options, includeScreenshot: false };
        break;
      case '--help':
      case '-h':
        showHelp();
        process.exit(0);
        break;
      default:
        fail(`Unknown argument: ${arg}`);
    }
  }

  if (!options.appId || !options.appId.trim()) {
    fail('Missing app id. Pass --app-id <package>.');
  }
  if (!options.outputDir || !options.outputDir.trim()) {
    fail('Missing output directory. Pass --output-dir <path>.');
  }
  if (!Number.isInteger(options.minutes) || options.minutes < 0) {
    fail('Minutes must be an integer >= 0.');
  }

  return options;
}

function showHelp() {
  writeLine('Prototype daily debug capture');
  writeLine('');
  writeLine('Usage:');
  writeLine('  node scripts/system-debug-capture.js [options]');
  writeLine('');
  writeLine('Options:');
  writeLine('  --device <serial>        Target adb device serial');
  writeLine('  --app-id <package>       Android application id (default: com.atombits.pocopaw)');
  writeLine('  --output-dir <path>      Capture output directory (default: logs/captures)');
  writeLine('  --minutes <n>            Recent logcat window in minutes (default: 5)');
  writeLine('  --jd-package <package>   JD package to verify (default: com.jingdong.app.mall)');
  writeLine('  --no-ui-dump             Skip uiautomator dump');
  writeLine('  --no-screenshot          Skip screenshot capture');
}

function runProcess(command, args, options = {}) {
  const result = spawnSync(command, args, {
    encoding: options.encoding === null ? null : (options.encoding || 'utf8'),
    maxBuffer: 1024 * 1024 * 32,
    shell: false
  });

  return {
    success: result.status === 0,
    stdout: result.stdout,
    stderr: result.stderr,
    code: result.status,
    error: result.error ? result.error.message : null
  };
}

function runAdb(device, args, options = {}) {
  const scopedArgs = device ? ['-s', device, ...args] : args;
  return runProcess('adb', scopedArgs, options);
}

function parseConnectedDevices(devicesOutput) {
  return devicesOutput
    .split(/\r?\n/)
    .slice(1)
    .map(line => line.trim())
    .filter(Boolean)
    .map(line => {
      const [serial, state] = line.split(/\s+/);
      return { serial, state };
    })
    .filter(device => device.state === 'device');
}

function resolveDevice(requestedDevice) {
  const devicesResult = runAdb(null, ['devices']);
  if (!devicesResult.success) {
    fail(`Failed to run adb devices: ${stringValue(devicesResult.stderr) || devicesResult.error || 'unknown error'}`);
  }

  const devices = parseConnectedDevices(stringValue(devicesResult.stdout));
  if (requestedDevice) {
    const match = devices.find(device => device.serial === requestedDevice);
    if (!match) {
      fail(`Requested device ${requestedDevice} is not connected.`);
    }
    return match.serial;
  }

  if (devices.length === 0) {
    fail('No connected adb devices found.');
  }
  if (devices.length > 1) {
    const serials = devices.map(device => device.serial).join(', ');
    fail(`Multiple devices connected (${serials}). Pass --device <serial>.`);
  }
  return devices[0].serial;
}

function stringValue(value) {
  if (value === null || value === undefined) {
    return '';
  }
  return Buffer.isBuffer(value) ? value.toString('utf8') : String(value);
}

function fileTimestamp() {
  const now = new Date();
  const parts = [
    now.getFullYear(),
    String(now.getMonth() + 1).padStart(2, '0'),
    String(now.getDate()).padStart(2, '0'),
    String(now.getHours()).padStart(2, '0'),
    String(now.getMinutes()).padStart(2, '0'),
    String(now.getSeconds()).padStart(2, '0')
  ];
  return `${parts[0]}${parts[1]}${parts[2]}-${parts[3]}${parts[4]}${parts[5]}`;
}

function logcatSince(minutes) {
  const time = new Date(Date.now() - minutes * 60 * 1000);
  const month = String(time.getMonth() + 1).padStart(2, '0');
  const day = String(time.getDate()).padStart(2, '0');
  const hour = String(time.getHours()).padStart(2, '0');
  const minute = String(time.getMinutes()).padStart(2, '0');
  const second = String(time.getSeconds()).padStart(2, '0');
  return `${month}-${day} ${hour}:${minute}:${second}.000`;
}

function writeTextFile(filePath, content) {
  ensureDir(path.dirname(filePath));
  fs.writeFileSync(filePath, content, 'utf8');
}

function writeBinaryFile(filePath, content) {
  ensureDir(path.dirname(filePath));
  fs.writeFileSync(filePath, content);
}

function captureCommandText({ device, args, outputFile, failures, label }) {
  const result = runAdb(device, args);
  if (!result.success) {
    failures.push(`${label}: ${stringValue(result.stderr) || result.error || 'command failed'}`);
    return '';
  }
  const output = stringValue(result.stdout);
  writeTextFile(outputFile, output);
  return output;
}

function captureRunAsText({ device, appId, remotePath, outputFile, failures, label }) {
  const result = runAdb(device, ['shell', 'run-as', appId, 'cat', remotePath]);
  if (!result.success) {
    failures.push(`${label}: ${stringValue(result.stderr) || result.error || 'run-as failed'}`);
    return '';
  }
  const output = stringValue(result.stdout);
  writeTextFile(outputFile, output);
  return output;
}

function captureRunAsListing({ device, appId, outputFile, failures }) {
  const result = runAdb(device, ['shell', 'run-as', appId, 'ls', '-R', 'files']);
  if (!result.success) {
    failures.push(`app_files_listing: ${stringValue(result.stderr) || result.error || 'run-as ls failed'}`);
    return '';
  }
  const output = stringValue(result.stdout);
  writeTextFile(outputFile, output);
  return output;
}

function captureUiDump({ device, outputFile, failures }) {
  const remotePath = '/sdcard/prototype_capture_ui.xml';
  const dumpResult = runAdb(device, ['shell', 'uiautomator', 'dump', remotePath]);
  if (!dumpResult.success) {
    failures.push(`ui_dump: ${stringValue(dumpResult.stderr) || dumpResult.error || 'uiautomator dump failed'}`);
    return false;
  }

  const dumpOutput = `${stringValue(dumpResult.stdout)}\n${stringValue(dumpResult.stderr)}`;
  const outputPathMatch = dumpOutput.match(/dumped to:\s*([^\r\n]+)/i);
  const candidatePaths = Array.from(new Set([
    remotePath,
    outputPathMatch ? outputPathMatch[1].trim() : null,
    '/sdcard/window_dump.xml',
    '/storage/emulated/0/window_dump.xml',
    '/sdcard/uidump.xml'
  ].filter(Boolean)));

  for (const candidatePath of candidatePaths) {
    const readResult = runAdb(device, ['exec-out', 'cat', candidatePath], { encoding: null });
    const readContent = stringValue(readResult.stdout);
    if (readResult.success && readContent.trim().includes('<hierarchy')) {
      writeTextFile(outputFile, readContent);
      runAdb(device, ['shell', 'rm', '-f', candidatePath]);
      return true;
    }

    const pullResult = runAdb(device, ['pull', candidatePath, outputFile]);
    if (pullResult.success) {
      runAdb(device, ['shell', 'rm', '-f', candidatePath]);
      return true;
    }
  }

  failures.push('ui_dump_pull: failed to read dumped UI XML from known paths');
  return false;
}

function captureScreenshot({ device, outputFile, failures }) {
  const screenshotResult = runAdb(device, ['exec-out', 'screencap', '-p'], { encoding: null });
  if (!screenshotResult.success || !Buffer.isBuffer(screenshotResult.stdout) || screenshotResult.stdout.length === 0) {
    failures.push(`screenshot: ${stringValue(screenshotResult.stderr) || screenshotResult.error || 'screencap failed'}`);
    return false;
  }
  writeBinaryFile(outputFile, screenshotResult.stdout);
  return true;
}

function captureFilteredLogcat({ device, outputFile, failures, minutes, appId, jdPackage }) {
  const timeBoundArgs = ['logcat', '-d', '-v', 'threadtime', '-T', logcatSince(minutes)];
  const boundedResult = runAdb(device, timeBoundArgs);
  const primaryResult = boundedResult.success ? boundedResult : runAdb(device, ['logcat', '-d', '-v', 'threadtime']);

  if (!primaryResult.success) {
    failures.push(`logcat: ${stringValue(primaryResult.stderr) || primaryResult.error || 'logcat failed'}`);
    return '';
  }

  const filtered = filterLogcat(stringValue(primaryResult.stdout), [
    ...LOGCAT_KEYWORDS,
    appId,
    jdPackage
  ]);
  writeTextFile(outputFile, filtered || 'No filtered logcat lines matched the configured keywords.\n');
  return filtered;
}

function filterLogcat(rawLogcat, keywords) {
  const loweredKeywords = keywords.map(keyword => keyword.toLowerCase());
  const lines = rawLogcat.split(/\r?\n/);
  return lines.filter(line => {
    const loweredLine = line.toLowerCase();
    return loweredKeywords.some(keyword => loweredLine.includes(keyword));
  }).join('\n');
}

function safeJsonParse(raw) {
  if (!raw || !raw.trim()) {
    return null;
  }
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

function summarizeStore(store) {
  if (!store) {
    return {
      available: false,
      stage: null,
      executionPreparation: null,
      currentExecutionRuntime: null,
      latestExecutionEvents: []
    };
  }

  const currentState = store.currentState || {};
  const executionPreparation = currentState.currentExecutionPreparation || null;
  const runtime = store.currentExecutionRuntime || null;
  const latestExecutionEvents = Array.isArray(store.executionEvents)
    ? store.executionEvents.slice(-5).map(event => ({
        summary: event.summary || '',
        startedAt: event.startedAt || null,
        candidateId: event.candidateId || null
      }))
    : [];

  return {
    available: true,
    stage: currentState.stage || null,
    activeCandidateId: currentState.activeCandidateId || null,
    messageCount: Array.isArray(store.messages) ? store.messages.length : 0,
    readyProcessAssetCount: Array.isArray(store.readyProcessAssets) ? store.readyProcessAssets.length : 0,
    processShortcutCount: Array.isArray(store.processShortcutAtlas) ? store.processShortcutAtlas.length : 0,
    pageEvidenceCount: Array.isArray(store.pageEvidenceAssets) ? store.pageEvidenceAssets.length : 0,
    executionPreparation: executionPreparation ? {
      objective: executionPreparation.objective || '',
      selectedToolId: executionPreparation.selectedToolId || null,
      selectedProcessId: executionPreparation.selectedProcessId || null,
      executionGateFlag: executionPreparation.executionGateFlag || null,
      executionLifecycleStatus: executionPreparation.executionLifecycleStatus || null,
      canStartExecution: Boolean(executionPreparation.canStartExecution),
      missingInformation: Array.isArray(executionPreparation.missingInformation) ? executionPreparation.missingInformation : []
    } : null,
    currentExecutionRuntime: runtime ? {
      selectedToolId: runtime.executionResult?.selectedToolId || runtime.handoffBrief?.selectedToolId || null,
      selectedProcessId: runtime.executionResult?.selectedProcessId || runtime.handoffBrief?.selectedProcessId || null,
      lifecycleStatus: runtime.executionResult?.lifecycleStatus || null,
      summary: runtime.executionResult?.summary || '',
      traceStepCount: Array.isArray(runtime.executionTrace?.steps) ? runtime.executionTrace.steps.length : 0
    } : null,
    latestExecutionEvents
  };
}

function summarizeToolspace(toolspace) {
  if (!toolspace) {
    return {
      available: false,
      updatedAt: null,
      total: 0,
      system: 0,
      app: 0,
      mcp: 0,
      jdCapabilityPresent: false
    };
  }

  const capabilities = Array.isArray(toolspace.capabilities) ? toolspace.capabilities : [];
  const domainCounts = capabilities.reduce((counts, capability) => {
    const domain = String(capability.domain || capability.tool_type || '').toUpperCase();
    return {
      ...counts,
      [domain]: (counts[domain] || 0) + 1
    };
  }, {});

  return {
    available: true,
    updatedAt: toolspace.updated_at || toolspace.updatedAt || null,
    total: capabilities.length,
    system: domainCounts.SYSTEM || 0,
    app: domainCounts.APP || 0,
    mcp: domainCounts.MCP || 0,
    jdCapabilityPresent: capabilities.some(capability => {
      const capabilityId = capability.capability_id || capability.capabilityId || '';
      const source = capability.source || '';
      return capabilityId === 'app.com.jingdong.app.mall.open' || source === 'com.jingdong.app.mall';
    })
  };
}

function extractFocusHints(activityTopText, activityListText, windowText, appId, jdPackage) {
  const combinedLines = [activityTopText, activityListText, windowText]
    .filter(Boolean)
    .join('\n')
    .split(/\r?\n/)
    .filter(Boolean);

  return combinedLines.filter(line => {
    return FOCUS_PATTERNS.some(pattern => line.includes(pattern)) || line.includes(appId) || line.includes(jdPackage);
  });
}

function formatSummaryText(summary) {
  const focusLines = summary.focusHints.length > 0 ? summary.focusHints.join('\n') : 'No focus/activity hints found.';
  const executionEvents = summary.store.latestExecutionEvents.length > 0
    ? summary.store.latestExecutionEvents.map(event => `- ${event.summary} @ ${event.startedAt || 'unknown'}`).join('\n')
    : 'No execution events found.';
  const failures = summary.failures.length > 0 ? summary.failures.map(item => `- ${item}`).join('\n') : 'None';

  return [
    `captured_at=${summary.capturedAt}`,
    `device_serial=${summary.deviceSerial}`,
    `app_id=${summary.appId}`,
    `jd_package=${summary.jdPackage}`,
    `jd_installed=${summary.jdInstalled}`,
    `jd_seen_in_activity_stack=${summary.jdSeenInActivityStack}`,
    '',
    '[focus_hints]',
    focusLines,
    '',
    '[prototype_store]',
    `stage=${summary.store.stage || '-'}`,
    `active_candidate_id=${summary.store.activeCandidateId || '-'}`,
    `execution_preparation_present=${Boolean(summary.store.executionPreparation)}`,
    `execution_preparation_selected_tool=${summary.store.executionPreparation?.selectedToolId || '-'}`,
    `execution_preparation_selected_process=${summary.store.executionPreparation?.selectedProcessId || '-'}`,
    `execution_preparation_gate=${summary.store.executionPreparation?.executionGateFlag || '-'}`,
    `execution_runtime_present=${Boolean(summary.store.currentExecutionRuntime)}`,
    `execution_runtime_selected_tool=${summary.store.currentExecutionRuntime?.selectedToolId || '-'}`,
    `execution_runtime_selected_process=${summary.store.currentExecutionRuntime?.selectedProcessId || '-'}`,
    `execution_runtime_lifecycle=${summary.store.currentExecutionRuntime?.lifecycleStatus || '-'}`,
    `execution_runtime_summary=${summary.store.currentExecutionRuntime?.summary || '-'}`,
    `ready_process_asset_count=${summary.store.readyProcessAssetCount}`,
    `process_shortcut_count=${summary.store.processShortcutCount}`,
    `page_evidence_count=${summary.store.pageEvidenceCount}`,
    '',
    '[latest_execution_events]',
    executionEvents,
    '',
    '[toolspace]',
    `available=${summary.toolspace.available}`,
    `updated_at=${summary.toolspace.updatedAt || '-'}`,
    `total=${summary.toolspace.total}`,
    `system=${summary.toolspace.system}`,
    `app=${summary.toolspace.app}`,
    `mcp=${summary.toolspace.mcp}`,
    `jd_capability_present=${summary.toolspace.jdCapabilityPresent}`,
    '',
    '[failures]',
    failures,
    ''
  ].join('\n');
}

function main() {
  const options = parseArgs(process.argv);
  if (!commandExists('adb')) {
    fail('adb is not available in PATH.');
  }

  const deviceSerial = resolveDevice(options.device);
  const captureTimestamp = fileTimestamp();
  const outputDir = path.resolve(options.outputDir);
  ensureDir(outputDir);

  const failures = [];
  const outputFiles = {
    activityTop: path.join(outputDir, `activity_top_${captureTimestamp}.txt`),
    activities: path.join(outputDir, `activity_activities_${captureTimestamp}.txt`),
    window: path.join(outputDir, `window_windows_${captureTimestamp}.txt`),
    focus: path.join(outputDir, `activity_focus_${captureTimestamp}.txt`),
    packages: path.join(outputDir, `packages_${captureTimestamp}.txt`),
    appFiles: path.join(outputDir, `app_files_${captureTimestamp}.txt`),
    prototypeStore: path.join(outputDir, `prototype_store_${captureTimestamp}.json`),
    toolspaceCatalog: path.join(outputDir, `toolspace_catalog_${captureTimestamp}.json`),
    logcat: path.join(outputDir, `logcat_filtered_${captureTimestamp}.txt`),
    summaryJson: path.join(outputDir, `summary_${captureTimestamp}.json`),
    summaryTxt: path.join(outputDir, `summary_${captureTimestamp}.txt`),
    uiDump: path.join(outputDir, `ui_dump_${captureTimestamp}.xml`),
    screenshot: path.join(outputDir, `screenshot_${captureTimestamp}.png`)
  };

  const activityTopText = captureCommandText({
    device: deviceSerial,
    args: ['shell', 'dumpsys', 'activity', 'top'],
    outputFile: outputFiles.activityTop,
    failures,
    label: 'activity_top'
  });
  const activityListText = captureCommandText({
    device: deviceSerial,
    args: ['shell', 'dumpsys', 'activity', 'activities'],
    outputFile: outputFiles.activities,
    failures,
    label: 'activity_activities'
  });
  const windowText = captureCommandText({
    device: deviceSerial,
    args: ['shell', 'dumpsys', 'window', 'windows'],
    outputFile: outputFiles.window,
    failures,
    label: 'window_windows'
  });
  const packageText = captureCommandText({
    device: deviceSerial,
    args: ['shell', 'pm', 'list', 'packages', options.jdPackage],
    outputFile: outputFiles.packages,
    failures,
    label: 'packages'
  });
  captureRunAsListing({
    device: deviceSerial,
    appId: options.appId,
    outputFile: outputFiles.appFiles,
    failures
  });
  const storeRaw = captureRunAsText({
    device: deviceSerial,
    appId: options.appId,
    remotePath: 'files/prototype_store.json',
    outputFile: outputFiles.prototypeStore,
    failures,
    label: 'prototype_store'
  });
  const toolspaceRaw = captureRunAsText({
    device: deviceSerial,
    appId: options.appId,
    remotePath: 'files/toolspace/toolspace_catalog.json',
    outputFile: outputFiles.toolspaceCatalog,
    failures,
    label: 'toolspace_catalog'
  });
  captureFilteredLogcat({
    device: deviceSerial,
    outputFile: outputFiles.logcat,
    failures,
    minutes: options.minutes,
    appId: options.appId,
    jdPackage: options.jdPackage
  });

  if (options.includeUiDump) {
    captureUiDump({ device: deviceSerial, outputFile: outputFiles.uiDump, failures });
  }
  if (options.includeScreenshot) {
    captureScreenshot({ device: deviceSerial, outputFile: outputFiles.screenshot, failures });
  }

  const focusHints = extractFocusHints(activityTopText, activityListText, windowText, options.appId, options.jdPackage);
  writeTextFile(outputFiles.focus, focusHints.join('\n') || 'No matching focus hints found.\n');

  const storeSummary = summarizeStore(safeJsonParse(storeRaw));
  const toolspaceSummary = summarizeToolspace(safeJsonParse(toolspaceRaw));
  const summary = {
    capturedAt: new Date().toISOString(),
    deviceSerial,
    appId: options.appId,
    jdPackage: options.jdPackage,
    jdInstalled: packageText.includes(options.jdPackage),
    jdSeenInActivityStack: `${activityTopText}\n${activityListText}\n${windowText}`.includes(options.jdPackage),
    focusHints,
    store: storeSummary,
    toolspace: toolspaceSummary,
    failures,
    files: outputFiles
  };

  writeTextFile(outputFiles.summaryJson, JSON.stringify(summary, null, 2));
  writeTextFile(outputFiles.summaryTxt, formatSummaryText(summary));

  writeLine(`Capture complete: ${outputDir}`);
  writeLine(`Summary: ${outputFiles.summaryTxt}`);
  if (failures.length > 0) {
    writeLine('Partial failures were recorded in the summary file.');
  }
}

main();