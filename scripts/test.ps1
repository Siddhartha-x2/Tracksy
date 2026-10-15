param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'database.ps1')
if (!$NoBuild) { & (Join-Path $PSScriptRoot 'build.ps1') }
$runtime = Join-Path $root 'work/runtime'
$settings = Get-Content (Join-Path $runtime 'local-settings.json') -Raw | ConvertFrom-Json
$jdk = Get-ChildItem $runtime -Directory -Filter 'jdk-*' | Select-Object -First 1
$env:DB_URL = "jdbc:postgresql://127.0.0.1:$($settings.port)/tracksy_test"
$env:DATABASE_URL = $env:DB_URL
$env:DB_USER = $settings.username
$env:DATABASE_USERNAME = $settings.username
$env:DB_PASSWORD = $settings.password
$env:DATABASE_PASSWORD = $settings.password
$env:TRACKSY_UPLOAD_DIR = Join-Path $root 'work/test-uploads'
$env:TRACKSY_UPLOAD_DIRECTORY = $env:TRACKSY_UPLOAD_DIR
$env:SPRING_PROFILES_ACTIVE = 'demo'
$env:TEST_BASE_URL = 'http://127.0.0.1:4010'
$process = Start-Process -FilePath (Join-Path $jdk.FullName 'bin/java.exe') -ArgumentList '-jar',(Join-Path $root 'backend/target/tracksy-1.0.0.jar'),'--server.port=4010' -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtime 'test-server.log') -RedirectStandardError (Join-Path $runtime 'test-server-errors.log') -PassThru
try {
  $ready = $false
  for ($i=0; $i -lt 60; $i++) {
    if ($process.HasExited) { throw 'Test backend exited. Read work/runtime/test-server.log.' }
    try { $health = Invoke-RestMethod "$($env:TEST_BASE_URL)/api/health" -TimeoutSec 2; if ($health.status -eq 'ok') { $ready=$true; break } } catch {}
    Start-Sleep -Milliseconds 500
  }
  if (!$ready) { throw 'Test backend did not become ready.' }
  Push-Location $root
  try {
    & node.exe --test tests/workflow.test.mjs
    if ($LASTEXITCODE) { throw 'Integration checks failed.' }
  } finally { Pop-Location }
} finally {
  if (!$process.HasExited) { Stop-Process -Id $process.Id }
}
