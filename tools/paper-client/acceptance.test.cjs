'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const { Paper, assertResourceHeadroom, assertPaperLaunchLimits, freeCommitMemoryBytesAsync, RESOURCE_LIMITS } = require('./acceptance.cjs');
const { assertDiskHeadroom, MINIMUM_FREE_DISK_BYTES } = require('./lifecycle-logistics-acceptance.cjs');

function resourceFailureProbe(ready) {
  const writes = [];
  let killed = null;
  const paper = new Paper({}, 'test-runtime', 1);
  paper.lines = ready ? ['Done (0.1s)!'] : [];
  paper.out = { write() {} };
  paper.child = {
    pid: 123,
    stdin: { writable: true, write(value) { writes.push(value); } },
    kill(signal) { killed = signal; }
  };
  paper.stopForResourceFailure(new Error('test resource failure'));
  return { paper, writes, killed: () => killed };
}

function completedPaperStop(resourceStopForced) {
  const paper = new Paper({}, 'test-runtime', 1);
  paper.error = new Error('test resource failure');
  paper.resourceStopForced = resourceStopForced;
  paper.stopCommandSent = true;
  paper.child = { pid: 123, stdin: { writable: true, write() {} } };
  paper.exit = { code: 0, signal: null };
  paper.exited = Promise.resolve();
  paper.out = { write() {}, end(callback) { callback(); } };
  return paper;
}

test('acceptance resource preflight accepts bounded Node heap with the required startup reserve', () => {
  const result = assertResourceHeadroom(RESOURCE_LIMITS.minimumStartupFreeMemoryBytes, 384 * 1024 * 1024,
    RESOURCE_LIMITS.minimumStartupFreeCommitBytes);
  assert.equal(result.startupFreeMemoryBytes, RESOURCE_LIMITS.minimumStartupFreeMemoryBytes);
  assert.equal(result.startupFreeCommitBytes, RESOURCE_LIMITS.minimumStartupFreeCommitBytes);
  assert.equal(result.nodeHeapLimitBytes, 384 * 1024 * 1024);
});

test('acceptance resource preflight rejects startup below the system memory reserve', () => {
  assert.throws(() => assertResourceHeadroom(RESOURCE_LIMITS.minimumStartupFreeMemoryBytes - 1, 384 * 1024 * 1024,
    RESOURCE_LIMITS.minimumStartupFreeCommitBytes),
    /at least 1536 MiB free physical memory/);
});

test('acceptance resource preflight rejects insufficient Windows commit reserve', () => {
  assert.throws(() => assertResourceHeadroom(RESOURCE_LIMITS.minimumStartupFreeMemoryBytes, 384 * 1024 * 1024,
    RESOURCE_LIMITS.minimumStartupFreeCommitBytes - 1), /at least 2048 MiB free commit memory/);
});

test('acceptance resource preflight rejects an unbounded Node heap', () => {
  assert.throws(() => assertResourceHeadroom(RESOURCE_LIMITS.minimumStartupFreeMemoryBytes,
    RESOURCE_LIMITS.maximumNodeHeapBytes + 1, RESOURCE_LIMITS.minimumStartupFreeCommitBytes),
    /Node heap limit must be at most 512 MiB/);
});

test('runtime commit sampling is asynchronous, bounded and validates Windows output', async () => {
  let options;
  let command;
  const result = await freeCommitMemoryBytesAsync((file, args, suppliedOptions, callback) => {
    options = suppliedOptions;
    command = args.at(-1);
    callback(null, '2097152\r\n');
  }, 'win32');
  assert.equal(result, 2 * 1024 * 1024 * 1024);
  assert.equal(options.timeout, 10000);
  assert.equal(options.windowsHide, true);
  assert.match(command, /GlobalMemoryStatusEx/);
  assert.doesNotMatch(command, /Get-CimInstance/);

  let attempts = 0;
  const retried = await freeCommitMemoryBytesAsync((file, args, suppliedOptions, callback) => {
    attempts++;
    if (attempts === 1) { callback(new Error('temporary PowerShell startup failure')); return; }
    callback(null, '2097152\r\n');
  }, 'win32');
  assert.equal(retried, 2 * 1024 * 1024 * 1024);
  assert.equal(attempts, 2);

  attempts = 0;
  await assert.rejects(freeCommitMemoryBytesAsync((file, args, suppliedOptions, callback) => {
    attempts++;
    callback(null, 'not-a-number');
  }, 'win32'), /Could not read available Windows commit memory/);
  assert.equal(attempts, 2);
});

test('Paper startup reserve grows with the configured JVM and Node heaps', () => {
  const paperHeap = 512 * 1024 * 1024;
  const nodeHeap = 384 * 1024 * 1024;
  const required = paperHeap + nodeHeap + RESOURCE_LIMITS.minimumStartupNonHeapReserveBytes
    + RESOURCE_LIMITS.minimumRuntimeFreeMemoryBytes;
  const result = assertPaperLaunchLimits(['-Xms128m', '-Xmx512m'], {}, required, nodeHeap, required);
  assert.equal(result.requiredStartupFreeMemoryBytes, required);
  assert.equal(result.requiredStartupFreeCommitBytes, required);
  assert.equal(result.minimumStartupNonHeapReserveBytes, RESOURCE_LIMITS.minimumStartupNonHeapReserveBytes);
  assert.throws(() => assertPaperLaunchLimits(['-Xms128m', '-Xmx512m'], {}, required - 1, nodeHeap,
    required), /requires .* free physical memory/);
  assert.throws(() => assertPaperLaunchLimits(['-Xms128m', '-Xmx512m'], {}, required, nodeHeap,
    required - 1), /requires .* free commit memory/);
});

test('Paper startup rejects implicit JVM options and oversized heap overrides', () => {
  const freeMemory = 3 * 1024 * 1024 * 1024;
  const freeCommit = 3 * 1024 * 1024 * 1024;
  assert.throws(() => assertPaperLaunchLimits(['-Xmx512m'], { JAVA_TOOL_OPTIONS: '-Xmx2g' }, freeMemory,
    384 * 1024 * 1024, freeCommit), /Unexpected JVM option injection/);
  assert.throws(() => assertPaperLaunchLimits(['-Xmx1g'], {}, freeMemory, 384 * 1024 * 1024, freeCommit),
    /Paper maximum heap must not exceed 768 MiB/);
});

test('lifecycle Paper preflight requires free disk space before starting Java', () => {
  assert.equal(assertDiskHeadroom(MINIMUM_FREE_DISK_BYTES), MINIMUM_FREE_DISK_BYTES);
  assert.throws(() => assertDiskHeadroom(MINIMUM_FREE_DISK_BYTES - 1), /at least 1024 MiB free disk space/);
});

test('resource failure during Paper startup immediately terminates the owned child', () => {
  const { paper, writes, killed } = resourceFailureProbe(false);
  assert.deepEqual(writes, []);
  assert.equal(killed(), 'SIGKILL');
  assert.equal(paper.error.message, 'test resource failure');
  assert.equal(paper.resourceStopForced, true);
});

test('resource failure after Paper readiness requests a clean stop with a force-stop fallback', () => {
  const { paper, writes, killed } = resourceFailureProbe(true);
  assert.deepEqual(writes, ['stop\n']);
  assert.equal(killed(), null);
  assert.equal(paper.resourceStopForced, false);
  assert.ok(paper.resourceStopTimer);
  clearTimeout(paper.resourceStopTimer);
  paper.resourceStopTimer = null;
});

test('clean resource-triggered shutdown preserves the primary failure without a cleanup error', async () => {
  const paper = completedPaperStop(false);
  const failure = paper.error;
  await assert.rejects(paper.stop(), error => error === failure);
  assert.equal(paper.child, null);
  assert.equal(paper.out, null);
});

test('forced resource shutdown reports a teardown failure separately', async () => {
  const paper = completedPaperStop(true);
  const failure = paper.error;
  await assert.rejects(paper.stop(), error => error.cause === failure && /force-terminated/.test(error.message));
  assert.equal(paper.child, null);
  assert.equal(paper.out, null);
});

test('unresponsive Paper shutdown closes resource evidence after terminating only its owned child', async () => {
  const paper = new Paper({ stopTimeoutMs: 1 }, 'test-runtime', 1);
  const writes = [];
  let killed = null;
  paper.exited = new Promise(resolve => {
    paper.child = {
      pid: 456,
      stdin: { writable: false },
      kill(signal) { killed = signal; paper.exit = { code: null, signal }; resolve(); }
    };
  });
  paper.out = {
    write(value) { writes.push(value); },
    end(callback) { callback(); }
  };

  await assert.rejects(paper.stop(), /Owned Paper child did not stop cleanly: PID 456/);
  assert.equal(killed, 'SIGKILL');
  assert.equal(paper.child, null);
  assert.equal(paper.out, null);
  assert.match(writes.join(''), /STARWORLD_RESOURCE_SUMMARY/);
});
