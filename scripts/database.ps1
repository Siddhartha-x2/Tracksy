$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $root 'work/runtime'
$bin = Join-Path $runtime 'postgresql/pgsql/bin'
$data = Join-Path $runtime 'pg-data'
$settingsPath = Join-Path $runtime 'local-settings.json'
if (!(Test-Path (Join-Path $bin 'initdb.exe'))) { throw 'PostgreSQL tools missing. Run npm run setup.' }
if (!(Test-Path $settingsPath)) {
  $random = New-Object byte[] 32
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  $rng.GetBytes($random)
  $adminPassword = [Convert]::ToBase64String($random)
  $appRandom = New-Object byte[] 32
  $rng.GetBytes($appRandom)
  $appPassword = [Convert]::ToBase64String($appRandom)
  $rng.Dispose()
  @{ port = 54329; username = 'tracksy'; password = $appPassword; adminPassword = $adminPassword } | ConvertTo-Json | Set-Content $settingsPath
}
$settings = Get-Content $settingsPath -Raw | ConvertFrom-Json
if (!(Test-Path (Join-Path $data 'PG_VERSION'))) {
  $pwfile = Join-Path $runtime 'bootstrap-password.txt'
  Set-Content -Path $pwfile -Value $settings.adminPassword -NoNewline
  & (Join-Path $bin 'initdb.exe') -D $data -U postgres -A scram-sha-256 -E UTF8 --locale=C "--pwfile=$pwfile"
  if ($LASTEXITCODE) { throw 'Database initialization failed.' }
  Set-Content -Path (Join-Path $data 'tracksy.conf') -Value "listen_addresses='127.0.0.1'`nport=$($settings.port)`nmax_connections=100"
  Add-Content -Path (Join-Path $data 'postgresql.conf') -Value "`ninclude='tracksy.conf'"
  Set-Content -Path $pwfile -Value ''
}
& (Join-Path $bin 'pg_ctl.exe') -D $data status 2>$null | Out-Null
if ($LASTEXITCODE) {
  & (Join-Path $bin 'pg_ctl.exe') -D $data -l (Join-Path $runtime 'postgresql.log') -w start
  if ($LASTEXITCODE) { throw 'PostgreSQL did not start.' }
}
$env:PGPASSWORD = $settings.adminPassword
$env:PGCONNECT_TIMEOUT = '5'
$psql = Join-Path $bin 'psql.exe'
try {
$role = & $psql -w -h 127.0.0.1 -p $settings.port -U postgres -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='tracksy'"
if ($LASTEXITCODE) { throw 'Database bootstrap connection failed.' }
if (!$role) {
  "CREATE ROLE tracksy LOGIN PASSWORD '$($settings.password.Replace("'","''"))';" | & $psql -w -h 127.0.0.1 -p $settings.port -U postgres -d postgres -v ON_ERROR_STOP=1
  if ($LASTEXITCODE) { throw 'Database role creation failed.' }
}
foreach ($database in @('tracksy', 'tracksy_test')) {
  $exists = & $psql -w -h 127.0.0.1 -p $settings.port -U postgres -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$database'"
  if ($LASTEXITCODE) { throw 'Database connection failed.' }
  if (!$exists) { & $psql -w -h 127.0.0.1 -p $settings.port -U postgres -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $database OWNER tracksy" }
  if ($LASTEXITCODE) { throw 'Database creation failed.' }
}
} finally { $env:PGPASSWORD = '' }
Write-Host "PostgreSQL ready on 127.0.0.1:$($settings.port)."
