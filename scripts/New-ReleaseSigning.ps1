param(
    [string]$OutputDirectory = (Join-Path $env:LOCALAPPDATA 'PoswelDosirak\Signing'),
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$keytool = Join-Path $JavaHome 'bin\keytool.exe'
if (!(Test-Path -LiteralPath $keytool -PathType Leaf)) { throw 'Set JAVA_HOME to an installed JDK.' }
$keystore = Join-Path $OutputDirectory 'poswel-release.p12'
$credentials = Join-Path $OutputDirectory 'release-credentials.clixml'
if ((Test-Path -LiteralPath $keystore) -or (Test-Path -LiteralPath $credentials)) {
    throw 'Signing material already exists. Nothing was overwritten.'
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = [Security.AccessControl.DirectorySecurity]::new()
$acl.SetAccessRuleProtection($true, $false)
$inherit = [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'
$propagation = [Security.AccessControl.PropagationFlags]::None
$allow = [Security.AccessControl.AccessControlType]::Allow
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', $inherit, $propagation, $allow))
$system = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($system, 'FullControl', $inherit, $propagation, $allow))
Set-Acl -LiteralPath $OutputDirectory -AclObject $acl
$secret = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:POSWEL_KEYTOOL_PASSWORD = $secret
try {
    & $keytool -genkeypair -noprompt -storetype PKCS12 -keystore $keystore -alias poswel-release `
        -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10000 `
        -dname 'CN=Poswel Dosirak Reservation (Unofficial)' `
        -storepass:env POSWEL_KEYTOOL_PASSWORD -keypass:env POSWEL_KEYTOOL_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Key generation failed. Preserve any existing signing files for inspection.' }
    $secure = ConvertTo-SecureString $secret -AsPlainText -Force
    [Management.Automation.PSCredential]::new('poswel-release', $secure) | Export-Clixml -LiteralPath $credentials
    Write-Output 'Release signing key created; password protected with Windows user DPAPI. No key or password was printed.'
} finally {
    Remove-Item Env:\POSWEL_KEYTOOL_PASSWORD -ErrorAction SilentlyContinue
    $secret = $null
}
