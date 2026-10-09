[CmdletBinding()]
param(
    [switch]$Apply,
    [string]$Destination = '.local-test/github-publish',
    [string]$ApkPath,
    [ValidateSet('debug', 'release')][string]$Variant = 'debug'
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$localRoot = [IO.Path]::GetFullPath((Join-Path $root '.local-test')) + [IO.Path]::DirectorySeparatorChar
$destinationRoot = [IO.Path]::GetFullPath((Join-Path $root $Destination))
if (-not $destinationRoot.StartsWith($localRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Destination must be a child of the workspace .local-test directory.'
}

function Assert-NoReparsePoint([string]$Path) {
    $current = [IO.Path]::GetFullPath($Path)
    while ($current) {
        $item = Get-Item -LiteralPath $current -Force -ErrorAction SilentlyContinue
        if ($item -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Reparse points are not permitted in export paths.'
        }
        if ($current -eq $root) { break }
        $parent = Split-Path -Parent $current
        if (-not $parent -or $parent -eq $current) { break }
        $current = $parent
    }
}
Assert-NoReparsePoint $destinationRoot
Assert-NoReparsePoint $localRoot

$publicFiles = @(
    'README.md', 'LICENSE', 'PRIVACY.md', 'THIRD_PARTY_NOTICES.md', 'RELEASE_NOTES.md',
    'scripts/Export-PublicStaging.ps1', 'scripts/Sync-PublicDocuments.ps1',
    'scripts/PublicStaging.gitignore', 'scripts/New-ReleaseSigning.ps1',
    'android-app/README.md', 'android-app/build.gradle.kts', 'android-app/settings.gradle.kts',
    'android-app/gradle.properties', 'android-app/gradlew', 'android-app/gradlew.bat',
    'android-app/gradle/wrapper/gradle-wrapper.jar',
    'android-app/gradle/wrapper/gradle-wrapper.properties', 'android-app/app/build.gradle.kts',
    'android-app/app/src/main/AndroidManifest.xml', 'android-app/app/src/debug/AndroidManifest.xml',
    'android-app/app/src/androidTest/AndroidManifest.xml',
    'android-app/app/src/main/assets/privacy.txt',
    'android-app/app/src/main/assets/third_party_notices.txt'
)
$sourceFolders = @{
    'android-app/app/src/main/java' = @('.kt', '.java')
    'android-app/app/src/debug/java' = @('.kt', '.java')
    'android-app/app/src/test/java' = @('.kt', '.java')
    'android-app/app/src/androidTest/java' = @('.kt', '.java')
    'android-app/app/src/test/resources' = @('.html', '.json', '.xml', '.txt')
    'android-app/app/src/main/res' = @('.xml', '.png', '.webp', '.jpg', '.jpeg')
}
foreach ($folder in $sourceFolders.Keys) {
    $directory = Join-Path $root $folder
    if (-not (Test-Path -LiteralPath $directory)) { continue }
    Assert-NoReparsePoint $directory
    foreach ($file in Get-ChildItem -LiteralPath $directory -Recurse -File -Force) {
        Assert-NoReparsePoint $file.FullName
        if ($file.Extension -notin $sourceFolders[$folder]) {
            throw "Unexpected file type in allowlisted source folder: $folder"
        }
        $publicFiles += [IO.Path]::GetRelativePath($root, $file.FullName).Replace('\', '/')
    }
}
$publicFiles = @($publicFiles | Sort-Object -Unique)
foreach ($relative in $publicFiles) {
    $source = Join-Path $root $relative
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Missing public source: $relative" }
    Assert-NoReparsePoint $source
}
foreach ($pair in @(
    @('PRIVACY.md', 'android-app/app/src/main/assets/privacy.txt'),
    @('THIRD_PARTY_NOTICES.md', 'android-app/app/src/main/assets/third_party_notices.txt')
)) {
    if ((Get-FileHash -LiteralPath (Join-Path $root $pair[0])).Hash -ne
        (Get-FileHash -LiteralPath (Join-Path $root $pair[1])).Hash) {
        throw 'Document assets are stale. Run scripts/Sync-PublicDocuments.ps1 before building.'
    }
}

$gradle = Get-Content -LiteralPath (Join-Path $root 'android-app/app/build.gradle.kts') -Raw
$baseVersion = [regex]::Match($gradle, '(?m)^val appVersion\s*=\s*"([^"]+)"').Groups[1].Value
$versionCode = [regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value
$packageName = [regex]::Match($gradle, 'applicationId\s*=\s*"([^"]+)"').Groups[1].Value
$suffix = [regex]::Match($gradle, 'versionNameSuffix\s*=\s*"([^"]*)"').Groups[1].Value
if (-not $baseVersion -or -not $versionCode -or -not $packageName) {
    throw 'Cannot read the central app version. Update the exporter for the new build configuration.'
}
$versionName = if ($Variant -eq 'debug') { $baseVersion + $suffix } else { $baseVersion }
$entries = @($publicFiles | ForEach-Object {
    $source = Join-Path $root $_
    [ordered]@{ path = $_; source = $_; sha256 = (Get-FileHash -LiteralPath $source).Hash.ToLowerInvariant(); bytes = (Get-Item -LiteralPath $source).Length }
})
$entries += [ordered]@{
    path = '.gitignore'; source = 'scripts/PublicStaging.gitignore'
    sha256 = (Get-FileHash -LiteralPath (Join-Path $root 'scripts/PublicStaging.gitignore')).Hash.ToLowerInvariant()
    bytes = (Get-Item -LiteralPath (Join-Path $root 'scripts/PublicStaging.gitignore')).Length
}

$apk = $null
if ($ApkPath) {
    $apkSource = if ([IO.Path]::IsPathRooted($ApkPath)) { [IO.Path]::GetFullPath($ApkPath) }
        else { [IO.Path]::GetFullPath((Join-Path $root $ApkPath)) }
    if (-not $apkSource.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'APK must be inside this workspace.'
    }
    Assert-NoReparsePoint $apkSource
    if (-not (Test-Path -LiteralPath $apkSource -PathType Leaf)) { throw 'APK was not found.' }
    $sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android/Sdk'))
    $tools = $null
    foreach ($sdk in $sdkCandidates) {
        if (-not $sdk -or -not (Test-Path -LiteralPath (Join-Path $sdk 'build-tools'))) { continue }
        $tools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
            Sort-Object Name -Descending | Where-Object {
                (Test-Path -LiteralPath (Join-Path $_.FullName 'aapt.exe')) -and
                (Test-Path -LiteralPath (Join-Path $_.FullName 'apksigner.bat'))
            } | Select-Object -First 1
        if ($tools) { break }
    }
    if (-not $tools) { throw 'Android aapt and apksigner are required for APK export.' }
    # Windows aapt cannot reliably read non-ASCII absolute APK paths.
    Push-Location -LiteralPath (Split-Path -Parent $apkSource)
    try {
        $badging = (& (Join-Path $tools.FullName 'aapt.exe') dump badging (Split-Path -Leaf $apkSource) 2>&1) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw 'APK metadata inspection failed.' }
    } finally { Pop-Location }
    $identity = [regex]::Match($badging, "package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'")
    if (-not $identity.Success -or $identity.Groups[1].Value -ne $packageName -or
        $identity.Groups[2].Value -ne $versionCode -or $identity.Groups[3].Value -ne $versionName) {
        throw 'Source/APK package or version mismatch. No staging files were copied.'
    }
    $debuggable = $badging.Contains('application-debuggable')
    if ($debuggable -ne ($Variant -eq 'debug')) { throw 'APK debuggable flag does not match the requested variant.' }
    $signing = (& (Join-Path $tools.FullName 'apksigner.bat') verify --verbose --print-certs $apkSource 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $certificate = [regex]::Match($signing, 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)').Groups[1].Value
    if (-not $certificate) { throw 'APK signer certificate could not be identified.' }
    $apkRelative = "dist/poswel-dosirak-$versionName.apk"
    $apk = [ordered]@{ path = $apkRelative; sha256 = (Get-FileHash -LiteralPath $apkSource).Hash.ToLowerInvariant(); bytes = (Get-Item -LiteralPath $apkSource).Length; certificateSha256 = $certificate.ToLowerInvariant(); signatureVerified = $true; debuggable = $debuggable }
}

$expected = @($entries | ForEach-Object { $_.path }) + @('PUBLIC_MANIFEST.json')
if ($apk) { $expected += $apk.path }
$obsolete = @()
if (Test-Path -LiteralPath (Join-Path $destinationRoot '.git')) {
    $tracked = @(git -C $destinationRoot ls-files)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect staging Git files.' }
    $obsolete = @($tracked | Where-Object { $_ -notin $expected })
    if ($obsolete.Count -gt 0) {
        $obsolete | ForEach-Object { Write-Output "OBSOLETE_TRACKED $_" }
        if ($Apply) { throw 'Obsolete tracked files require a separate removal decision. Nothing was copied.' }
    }
    if ($Apply -and @(git -C $destinationRoot status --porcelain).Count -gt 0) {
        throw 'Staging has local changes. Review them before overwriting; nothing was copied.'
    }
}
if (-not $Apply) {
    [pscustomobject]@{ mode = 'preview'; sourceFiles = $entries.Count; versionName = $versionName; versionCode = [int]$versionCode; apkChecked = [bool]$apk; obsoleteTrackedFiles = $obsolete.Count } | ConvertTo-Json -Compress
    return
}

New-Item -ItemType Directory -Path $destinationRoot -Force | Out-Null
foreach ($entry in $entries) {
    $source = Join-Path $root $entry.source
    $target = Join-Path $destinationRoot $entry.path
    Assert-NoReparsePoint $target
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination $target -Force
    if ((Get-FileHash -LiteralPath $target).Hash.ToLowerInvariant() -ne $entry.sha256 -or
        (Get-FileHash -LiteralPath $source).Hash.ToLowerInvariant() -ne $entry.sha256) {
        throw "Source changed or copy hash differs: $($entry.path). Staging is incomplete; do not publish."
    }
}
if ($apk) {
    $target = Join-Path $destinationRoot $apk.path
    Assert-NoReparsePoint $target
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    Copy-Item -LiteralPath $apkSource -Destination $target -Force
    if ((Get-FileHash -LiteralPath $target).Hash.ToLowerInvariant() -ne $apk.sha256) { throw 'Staged APK hash differs; do not publish.' }
}
$manifest = [ordered]@{
    schema = 1; packageName = $packageName; versionName = $versionName; versionCode = [int]$versionCode
    variant = $Variant; generatedUtc = [DateTime]::UtcNow.ToString('o'); files = $entries; apk = $apk
}
$manifestPath = Join-Path $destinationRoot 'PUBLIC_MANIFEST.json'
Assert-NoReparsePoint $manifestPath
$manifestJson = ($manifest | ConvertTo-Json -Depth 6).Replace("`r`n", "`n") + "`n"
Set-Content -LiteralPath $manifestPath -Value $manifestJson -NoNewline -Encoding utf8NoBOM
[pscustomobject]@{ mode = 'copied'; sourceFiles = $entries.Count; versionName = $versionName; sourceHashMismatches = 0; apkIncluded = [bool]$apk; remoteChanged = $false } | ConvertTo-Json -Compress
