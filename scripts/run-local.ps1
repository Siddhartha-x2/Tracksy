param([switch]$NoBuild, [int]$Port = 4000)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$configPath = Join-Path $root '.env'
if (Test-Path $configPath) {
  $allowed = @('GITHUB_CLIENT_ID','GITHUB_CLIENT_SECRET','GITHUB_REDIRECT_URI','COOKIE_SECURE','APP_ORIGINS','SMTP_HOST','SMTP_PORT','SMTP_USER','SMTP_PASSWORD','SMTP_FROM','SMTP_SSL','PUBLIC_APP_URL')
  foreach ($line in (Get-Content $configPath)) {
    if ($line -match '^\s*([A-Z_][A-Z0-9_]*)\s*=\s*(.*)$' -and $allowed -contains $Matches[1]) {
      $key = $Matches[1]; $value = $Matches[2].Trim()
      if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) { $value = $value.Substring(1,$value.Length-2) }
      [Environment]::SetEnvironmentVariable($key,$value,'Process')
    }
  }
}
& (Join-Path $PSScriptRoot 'database.ps1')
if (!$NoBuild) { & (Join-Path $PSScriptRoot 'build.ps1') }
$runtime = Join-Path $root 'work/runtime'
$settings = Get-Content (Join-Path $runtime 'local-settings.json') -Raw | ConvertFrom-Json
$jdk = Get-ChildItem $runtime -Directory -Filter 'jdk-*' | Select-Object -First 1
$env:DB_URL = "jdbc:postgresql://127.0.0.1:$($settings.port)/tracksy"
$env:DB_USER = $settings.username
$env:DB_PASSWORD = $settings.password
$env:TRACKSY_UPLOAD_DIR = Join-Path $root 'work/uploads'
$env:SPRING_PROFILES_ACTIVE = 'demo'
$env:PORT = "$Port"
$java = Join-Path $jdk.FullName 'bin/java.exe'
$jar = Join-Path $root 'backend/target/tracksy-1.0.0.jar'
Push-Location $root
try { & $java -jar $jar "--server.port=$Port" } finally { Pop-Location }
