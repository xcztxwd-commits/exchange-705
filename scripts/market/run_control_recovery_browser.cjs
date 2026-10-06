// Only an isolated local browser fixture; never sends API calls to an application server.
const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const net = require('node:net')
const http = require('node:http')
const { spawn } = require('node:child_process')
const root = path.resolve(__dirname, '../..'), admin = path.join(root, 'exchange-admin')
const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds))
function playwrightPath() {
  if (process.env.PLAYWRIGHT_PATH) return process.env.PLAYWRIGHT_PATH
  const runtime = path.join(os.homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules')
  return require.resolve('playwright', { paths: [admin, root, runtime] })
}
function chromePath() {
  if (process.env.CHROME_PATH) return process.env.CHROME_PATH
  const candidates = [process.env.ProgramFiles, process.env['ProgramFiles(x86)'], process.env.LOCALAPPDATA].filter(Boolean)
    .flatMap(base => ['Google/Chrome/Application/chrome.exe', 'Microsoft/Edge/Application/msedge.exe'].map(name => path.join(base, name)))
  const executable = candidates.find(file => fs.existsSync(file))
  if (!executable) throw new Error('No installed Chrome/Edge; set CHROME_PATH. This runner does not install a browser.')
  return executable
}
async function unusedPort() {
  const reservation = net.createServer()
  await new Promise((resolve, reject) => { reservation.once('error', reject); reservation.listen(0, '127.0.0.1', resolve) })
  const port = reservation.address().port
  await new Promise(resolve => reservation.close(resolve))
  return port
}
async function ready(url, failed) {
  const until = Date.now() + 30000
  while (Date.now() < until) {
    const failure = failed(); if (failure) throw failure
    const available = await new Promise(resolve => {
      const request = http.get(url, { headers: { Accept: 'text/html' } }, response => { response.resume(); resolve(response.statusCode === 200) })
      request.setTimeout(1000, () => request.destroy()); request.on('error', () => resolve(false))
    })
    if (available) return
    await delay(200)
  }
  throw new Error('Isolated Vite did not become ready within 30 seconds')
}
;(async () => {
  const env = { ...process.env, PLAYWRIGHT_PATH: playwrightPath(), CHROME_PATH: chromePath() }
  let server, serverStopped = false, serverFailure, serverLog = ''
  try {
    if (env.ADMIN_QA_URL) {
      const url = new URL(env.ADMIN_QA_URL)
      if (!['http:', 'https:'].includes(url.protocol) || !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)) throw new Error('ADMIN_QA_URL must identify an explicitly configured loopback QA server')
    } else {
      const port = await unusedPort()
      env.ADMIN_QA_URL = `http://127.0.0.1:${port}`
      const vite = path.join(path.dirname(require.resolve('vite/package.json', { paths: [admin] })), 'bin/vite.js')
      // Direct node child, no shell or visible window; this invocation owns only this PID.
      server = spawn(process.execPath, [vite, '--host', '127.0.0.1', '--port', String(port), '--strictPort'], { cwd: admin, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
      for (const output of [server.stdout, server.stderr]) output.on('data', bytes => { serverLog = (serverLog + bytes).slice(-4000) })
      server.once('error', error => { serverFailure = error })
      server.once('exit', code => { serverStopped = true; serverFailure = new Error(`Owned Vite exited with ${code}`) })
      await ready(env.ADMIN_QA_URL, () => serverFailure)
    }
    const test = spawn(process.execPath, [path.join(admin, 'tests/aiControlRecovery.browser.cjs')], { cwd: root, env, windowsHide: true, stdio: 'inherit' })
    const code = await new Promise((resolve, reject) => { test.once('error', reject); test.once('exit', code => resolve(code ?? 1)) })
    process.exitCode = code
  } catch (error) {
    if (serverLog) console.error(serverLog)
    console.error(error.message); process.exitCode = 1
  } finally {
    if (server && !serverStopped) {
      server.kill('SIGTERM')
      await Promise.race([new Promise(resolve => server.once('exit', resolve)), delay(3000)])
      if (!serverStopped) server.kill('SIGKILL')
    }
  }
})()
