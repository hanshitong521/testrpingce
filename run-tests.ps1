$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path $root 'out'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $out | Out-Null
$sources = Get-ChildItem (Join-Path $root 'src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
if (-not $sources) { throw 'No Java source files found.' }
& javac --release 17 -Xlint:all -d $out @sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -ea -cp $out bench.queue.TaskQueueTest
exit $LASTEXITCODE
