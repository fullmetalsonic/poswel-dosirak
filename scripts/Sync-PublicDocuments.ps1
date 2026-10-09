[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$assets = Join-Path $root 'android-app/app/src/main/assets'
$pairs = @(
    @{ Source = 'PRIVACY.md'; Target = 'privacy.txt' },
    @{ Source = 'THIRD_PARTY_NOTICES.md'; Target = 'third_party_notices.txt' }
)
New-Item -ItemType Directory -Path $assets -Force | Out-Null
foreach ($pair in $pairs) {
    $source = Join-Path $root $pair.Source
    $target = Join-Path $assets $pair.Target
    Copy-Item -LiteralPath $source -Destination $target -Force
    if ((Get-FileHash -LiteralPath $source).Hash -ne (Get-FileHash -LiteralPath $target).Hash) {
        throw "Document asset hash mismatch: $($pair.Source)"
    }
    Write-Output "PASS $($pair.Source) -> assets/$($pair.Target)"
}
