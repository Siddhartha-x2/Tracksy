$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $root 'work/runtime'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null

# All tools stay in this workspace. No Windows services or global PATH changes.
if (!(Get-ChildItem $runtime -Directory -Filter 'jdk-*')) {
  Write-Host 'Downloading Eclipse Temurin Java 21...'
  $assets = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
  $package = $assets[0].binary.package
  $archive = Join-Path $runtime 'jdk21.zip'
  Invoke-WebRequest $package.link -OutFile $archive -TimeoutSec 240
  if ((Get-FileHash $archive -Algorithm SHA256).Hash.ToLower() -ne $package.checksum) { throw 'Java checksum mismatch.' }
  Expand-Archive $archive $runtime -Force
}
if (!(Get-ChildItem $runtime -Directory -Filter 'apache-maven-*')) {
  Write-Host 'Downloading Apache Maven...'
  $uri = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip'
  $archive = Join-Path $runtime 'maven.zip'
  Invoke-WebRequest $uri -OutFile $archive -TimeoutSec 120
  $expected = (Invoke-WebRequest "$uri.sha512").Content.Trim().ToLower()
  if ((Get-FileHash $archive -Algorithm SHA512).Hash.ToLower() -ne $expected) { throw 'Maven checksum mismatch.' }
  Expand-Archive $archive $runtime -Force
}
if (!(Test-Path (Join-Path $runtime 'postgresql/pgsql/bin/pg_ctl.exe'))) {
  Write-Host 'Downloading PostgreSQL 17 Windows binaries...'
  $archive = Join-Path $runtime 'postgresql.zip'
  Invoke-WebRequest 'https://sbp.enterprisedb.com/getfile.jsp?fileid=1260616' -OutFile $archive -TimeoutSec 240
  Expand-Archive $archive (Join-Path $runtime 'postgresql') -Force
}
Write-Host 'Workspace tools are ready. Run npm run dev.'
