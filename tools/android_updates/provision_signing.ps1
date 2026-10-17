param(
    [string]$SigningDirectory = "$env:USERPROFILE\.android\juggluco-update-signing",
    [string]$JavaDirectory = $env:JAVA_HOME,
    [string]$BuildTools = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0",
    [switch]$UploadToGitHub
)
$ErrorActionPreference = 'Stop'
if (-not $JavaDirectory) { throw 'Set JAVA_HOME to JDK 21 first.' }
$repoRoot = (Resolve-Path "$PSScriptRoot\..\..").Path
$signingRoot = [IO.Path]::GetFullPath($SigningDirectory)
if ($signingRoot.StartsWith($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Signing material must be outside the repository.'
}
New-Item -ItemType Directory -Path $signingRoot -Force | Out-Null
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = New-Object Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true, $false)
$acl.SetOwner($identity)
$acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(
    $identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
Set-Acl -LiteralPath $signingRoot -AclObject $acl
$key = Join-Path $signingRoot 'release.p12'
$passwordFile = Join-Path $signingRoot 'password.dpapi'
$lineage = Join-Path $signingRoot 'release.lineage'
$certificate = Join-Path $signingRoot 'release.cer'
if ((Test-Path $key) -and -not (Test-Path $passwordFile)) {
    throw 'Existing signing key has no password backup; refusing to replace it.'
}
if (-not (Test-Path $passwordFile)) {
    $bytes = New-Object byte[] 48
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $secure = ConvertTo-SecureString ([Convert]::ToBase64String($bytes)) -AsPlainText -Force
    # Generated secret, encrypted for this Windows user; never a plaintext password file.
    [IO.File]::WriteAllText($passwordFile, (ConvertFrom-SecureString $secure))
}
$secure = ConvertTo-SecureString ([IO.File]::ReadAllText($passwordFile))
$credential = New-Object Management.Automation.PSCredential('release', $secure)
$env:JUGGLUCO_SIGNING_PASSWORD = $credential.GetNetworkCredential().Password
$env:JUGGLUCO_LEGACY_PASSWORD = 'IDontKnow' # Public upstream key, not a release secret.
try {
    if (-not (Test-Path $key)) {
        & "$JavaDirectory\bin\keytool.exe" -genkeypair -keystore $key -storetype PKCS12 `
            -alias release -storepass:env JUGGLUCO_SIGNING_PASSWORD -keyalg RSA -keysize 3072 `
            -validity 10000 -dname 'CN=Juggluco Android Updates, O=anomaly51' -noprompt
        if ($LASTEXITCODE) { throw 'Key generation failed.' }
    }
    & "$JavaDirectory\bin\keytool.exe" -exportcert -keystore $key -alias release `
        -storepass:env JUGGLUCO_SIGNING_PASSWORD -file $certificate
    if ($LASTEXITCODE) { throw 'Certificate export failed.' }
    $fingerprint = (Get-FileHash -LiteralPath $certificate -Algorithm SHA256).Hash.ToLowerInvariant()
    if (-not (Test-Path $lineage)) {
        & "$BuildTools\apksigner.bat" rotate --out $lineage `
            --old-signer --ks "$repoRoot\Common\everyone.keystore" --ks-key-alias thealias `
            --ks-pass env:JUGGLUCO_LEGACY_PASSWORD --key-pass env:JUGGLUCO_LEGACY_PASSWORD `
            --set-installed-data true --set-shared-uid false --set-permission true `
            --set-rollback false --set-auth false `
            --new-signer --ks $key --ks-key-alias release `
            --ks-pass env:JUGGLUCO_SIGNING_PASSWORD --key-pass env:JUGGLUCO_SIGNING_PASSWORD
        if ($LASTEXITCODE) { throw 'Signing lineage generation failed.' }
    }
    if ($UploadToGitHub) {
        # Only explicit release tags may access the encrypted signing environment.
        '{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}' |
            gh api --method PUT repos/anomaly51/Juggluco/environments/android-updates --input - | Out-Null
        if ($LASTEXITCODE) { throw 'Could not configure GitHub environment.' }
        $policies = gh api repos/anomaly51/Juggluco/environments/android-updates/deployment-branch-policies | ConvertFrom-Json
        if (-not ($policies.branch_policies | Where-Object { $_.name -eq 'android-v*' -and $_.type -eq 'tag' })) {
            '{"name":"android-v*","type":"tag"}' | gh api --method POST `
                repos/anomaly51/Juggluco/environments/android-updates/deployment-branch-policies --input - | Out-Null
            if ($LASTEXITCODE) { throw 'Could not restrict signing to release tags.' }
        }
        $secrets = @{
            ANDROID_SIGNING_KEY_B64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($key))
            ANDROID_SIGNING_ALIAS = 'release'
            ANDROID_SIGNING_STORE_PASSWORD = $env:JUGGLUCO_SIGNING_PASSWORD
            ANDROID_SIGNING_KEY_PASSWORD = $env:JUGGLUCO_SIGNING_PASSWORD
            ANDROID_SIGNING_LINEAGE_B64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($lineage))
        }
        foreach ($name in $secrets.Keys) {
            $secrets[$name] | gh secret set $name --repo anomaly51/Juggluco --env android-updates
            if ($LASTEXITCODE) { throw "Could not upload secret $name" }
        }
        $fingerprint | gh variable set ANDROID_UPDATE_CERT_SHA256 --repo anomaly51/Juggluco --env android-updates
        if ($LASTEXITCODE) { throw 'Could not save public certificate fingerprint.' }
        $secrets.Clear()
    }
    Write-Output "Signing certificate SHA-256: $fingerprint"
    Write-Output "Encrypted signing material: $signingRoot"
} finally {
    Remove-Item Env:JUGGLUCO_SIGNING_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:JUGGLUCO_LEGACY_PASSWORD -ErrorAction SilentlyContinue
}
