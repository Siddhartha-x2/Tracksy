param([string]$Database = 'tracksy')
$ErrorActionPreference = 'Stop'
if ($Database -notmatch '^[a-zA-Z0-9_]+$') { throw 'Invalid database name.' }
$root = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $root 'work/runtime'
$settings = Get-Content (Join-Path $runtime 'local-settings.json') -Raw | ConvertFrom-Json
$backup = Join-Path $root ('work/backups/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $Database)
New-Item -ItemType Directory -Force -Path $backup | Out-Null
$env:PGPASSWORD = $settings.password
try {
  & (Join-Path $runtime 'postgresql/pgsql/bin/pg_dump.exe') -h 127.0.0.1 -p $settings.port -U $settings.username -d $Database -Fc -f (Join-Path $backup 'database.dump')
  if ($LASTEXITCODE) { throw 'Database backup failed.' }
  $uploads = Join-Path $root $(if ($Database -eq 'tracksy_test') { 'work/test-uploads' } else { 'work/uploads' })
  if (Test-Path $uploads) { Copy-Item -LiteralPath $uploads -Destination (Join-Path $backup 'uploads') -Recurse }
  @{ database=$Database; createdAt=(Get-Date).ToUniversalTime().ToString('o'); schema='Flyway'; applicationVersion='1.0.0' } | ConvertTo-Json | Set-Content (Join-Path $backup 'manifest.json')
  Write-Host "Backup saved to $backup"
} finally { $env:PGPASSWORD='' }
