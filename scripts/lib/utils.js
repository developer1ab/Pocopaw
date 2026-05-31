const fs = require('fs');
const { execSync } = require('child_process');

function ensureDir(dirPath) {
  if (!fs.existsSync(dirPath)) {
    fs.mkdirSync(dirPath, { recursive: true });
  }
  return dirPath;
}

function commandExists(command) {
  try {
    if (process.platform === 'win32') {
      execSync(`where ${command}`, { stdio: 'pipe' });
    } else {
      execSync(`which ${command}`, { stdio: 'pipe' });
    }
    return true;
  } catch {
    return false;
  }
}

module.exports = {
  ensureDir,
  commandExists
};
