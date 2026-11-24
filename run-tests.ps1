$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Out = Join-Path $Root 'out'
if (Test-Path $Out) { Remove-Item $Out -Recurse -Force }
New-Item -ItemType Directory -Path $Out | Out-Null
$Sources = Get-ChildItem (Join-Path $Root 'src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
& javac --release 17 -Xlint:all -d $Out $Sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -ea -cp $Out bench.commerce.CommerceTest
exit $LASTEXITCODE
