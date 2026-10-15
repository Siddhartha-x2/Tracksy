param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
  $runtime = Join-Path $root 'work/runtime'
  $jdk = Get-ChildItem -LiteralPath $runtime -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
  $maven = Get-ChildItem -LiteralPath $runtime -Directory -Filter 'apache-maven-*' -ErrorAction SilentlyContinue | Select-Object -First 1
  $mvn = if ($maven) { Join-Path $maven.FullName 'bin/mvn.cmd' } else { 'mvn.cmd' }
  if (!(Test-Path 'frontend/node_modules/typescript')) {
    & npm.cmd --prefix frontend ci --no-fund --no-audit
    if ($LASTEXITCODE) { throw 'Frontend dependency installation failed.' }
  }
  & npm.cmd --prefix frontend run build
  if ($LASTEXITCODE) { throw 'Frontend typecheck/build failed.' }
  $static = Join-Path $root 'backend/src/main/resources/static'
  $resolvedStatic = [IO.Path]::GetFullPath($static)
  $expectedStatic = [IO.Path]::GetFullPath((Join-Path $root 'backend/src/main/resources/static'))
  if ($resolvedStatic -ne $expectedStatic -or !$resolvedStatic.StartsWith([IO.Path]::GetFullPath($root) + [IO.Path]::DirectorySeparatorChar)) { throw 'Static output path is outside this project.' }
  if (Test-Path -LiteralPath $resolvedStatic) { Remove-Item -LiteralPath $resolvedStatic -Recurse -Force }
  New-Item -ItemType Directory -Path $static -Force | Out-Null
  Copy-Item -Path (Join-Path $root 'frontend/dist/*') -Destination $static -Recurse -Force
  $args = @('-f', 'backend/pom.xml', "-Dmaven.repo.local=$runtime/maven-cache", '-B', 'package')
  if ($SkipTests) { $args += '-DskipTests' }
  & $mvn @args
  if ($LASTEXITCODE) { throw 'Backend build failed.' }
} finally { Pop-Location }
