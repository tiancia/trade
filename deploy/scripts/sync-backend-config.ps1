# Run on Windows. Secrets travel only through SSH stdin, never command arguments.
[CmdletBinding()]
param(
    [string]$Server,
    [ValidateRange(1, 65535)][int]$Port = 22,
    [string]$IdentityFile,
    [string]$RemoteRoot = '/opt/trade',
    [switch]$CheckOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-LocalSetting {
    param([string]$Name)
    # setx changes the registry, while this terminal may still have an old value.
    foreach ($scope in @('User', 'Machine', 'Process')) {
        $value = [Environment]::GetEnvironmentVariable($Name, $scope)
        if (-not [string]::IsNullOrWhiteSpace($value)) { return $value }
    }
    return $null
}

function ConvertFrom-SettingScalar {
    param([string]$Text, [string]$Field)
    $textValue = $Text.Trim()
    if ($textValue.StartsWith("'")) {
        if ($textValue -notmatch "^'(?<value>(?:[^']|'')*)'\s*(?:#.*)?$") {
            throw "Unsupported YAML scalar: $Field"
        }
        return $Matches.value.Replace("''", "'")
    }
    if ($textValue.StartsWith('"')) {
        if ($textValue -notmatch '^(?<quoted>"(?:[^"\\]|\\.)*")\s*(?:#.*)?$') {
            throw "Unsupported YAML scalar: $Field"
        }
        try { return ConvertFrom-Json -InputObject $Matches.quoted }
        catch { throw "Unsupported YAML quoted scalar: $Field" }
    }
    return ($textValue -replace '\s+#.*$', '').Trim()
}

function Read-SettingScalars {
    param([string]$Path, [string[]]$Paths)
    $result = @{}
    $stack = [System.Collections.Generic.List[object]]::new()
    foreach ($line in [IO.File]::ReadLines($Path)) {
        if ($line -notmatch '^(?<indent> *)(?<key>[A-Za-z0-9_-]+):(?<value>.*)$') { continue }
        $indent = $Matches.indent.Length
        $key = $Matches.key
        $value = $Matches.value
        while ($stack.Count -gt 0 -and $stack[$stack.Count - 1].Indent -ge $indent) {
            $stack.RemoveAt($stack.Count - 1)
        }
        $pathKey = ((@($stack | ForEach-Object { $_.Key }) + $key) -join '.')
        if ($Paths -contains $pathKey) {
            if ($result.ContainsKey($pathKey)) { throw "Duplicate YAML field: $pathKey" }
            $result[$pathKey] = ConvertFrom-SettingScalar -Text $value -Field $pathKey
        }
        $stack.Add([pscustomobject]@{ Indent = $indent; Key = $key })
    }
    return $result
}

function Resolve-SettingScalar {
    param([string]$Scalar)
    if ($Scalar -match '^\$\{(?<name>[A-Za-z_][A-Za-z0-9_]*)(?::(?<fallback>.*))?\}$') {
        $name = $Matches.name
        $fallback = if ($Matches.ContainsKey('fallback')) { $Matches.fallback } else { '' }
        $environmentValue = Get-LocalSetting -Name $name
        if (-not [string]::IsNullOrWhiteSpace($environmentValue)) { return $environmentValue }
        return $fallback
    }
    return $Scalar
}

function Get-BackendConfigPatch {
    param([string]$YamlPath)
    # Fixed mappings for this repository; this is not a general YAML loader.
    $fields = [ordered]@{
        DEEPSEEK_API_KEY = ''
        TRADE_TELEGRAM_BOT_TOKEN = 'trade.telegram.bot-token'
        TRADE_X_TELEGRAM_CHAT_ID = 'trade.x.review.telegram.chat-id'
        TRADE_X_TELEGRAM_REVIEWER_USER_IDS = 'trade.x.review.telegram.reviewer-user-ids'
        TRADE_X_TARGET_USER_ID = 'trade.x.workflow.target-user-id'
        X_API_KEY = 'trade.x.client.api-key'
        X_API_SECRET = 'trade.x.client.api-secret'
        X_ACCESS_TOKEN = 'trade.x.client.access-token'
        X_ACCESS_TOKEN_SECRET = 'trade.x.client.access-token-secret'
    }
    $selectorPath = 'trade.ai.client.api-key-env-name'
    $scalars = Read-SettingScalars -Path $YamlPath -Paths (@($fields.Values) + $selectorPath)
    $patch = [ordered]@{}
    $missing = [System.Collections.Generic.List[string]]::new()
    foreach ($name in $fields.Keys) {
        $value = Get-LocalSetting -Name $name
        $pathKey = $fields[$name]
        if ([string]::IsNullOrWhiteSpace($value) -and $pathKey -and $scalars.ContainsKey($pathKey)) {
            $value = Resolve-SettingScalar -Scalar $scalars[$pathKey]
        }
        if ([string]::IsNullOrWhiteSpace($value) -and $name -eq 'DEEPSEEK_API_KEY') {
            $selector = Get-LocalSetting -Name 'TRADE_AI_API_KEY_ENV_NAME'
            if (-not $selector -and $scalars.ContainsKey($selectorPath)) {
                $selector = Resolve-SettingScalar -Scalar $scalars[$selectorPath]
            }
            if ($selector -and $selector -match '^[A-Za-z_][A-Za-z0-9_]*$') {
                $value = Get-LocalSetting -Name $selector
            }
        }
        if ([string]::IsNullOrWhiteSpace($value)) { $missing.Add($name); continue }
        if ($value.Length -gt 8192 -or $value -match '[\r\n\x00]' -or $value.Contains('${')) {
            throw "Invalid or unresolved configuration: $name"
        }
        if ($name -eq 'TRADE_X_TELEGRAM_REVIEWER_USER_IDS') { $value = $value -replace '\s', '' }
        if ($name -eq 'TRADE_X_TELEGRAM_CHAT_ID' -and $value -notmatch '^-?[0-9]+$') {
            throw "Expected a numeric chat ID: $name"
        }
        if ($name -eq 'TRADE_X_TARGET_USER_ID' -and $value -notmatch '^[0-9]+$') {
            throw "Expected a numeric X user ID: $name"
        }
        if ($name -eq 'TRADE_X_TELEGRAM_REVIEWER_USER_IDS' -and $value -notmatch '^[0-9]+(,[0-9]+)*$') {
            throw "Expected comma-separated numeric reviewer IDs: $name"
        }
        $patch[$name] = $value
    }
    if ($missing.Count -gt 0) { throw ('Missing local settings: ' + ($missing -join ', ')) }
    return $patch
}

function Get-RemoteConfigWriter {
    return @'
import base64, json, os, re, secrets, sys, tempfile
from pathlib import Path

KEYS = {
    'DEEPSEEK_API_KEY', 'TRADE_TELEGRAM_BOT_TOKEN',
    'TRADE_X_TELEGRAM_CHAT_ID', 'TRADE_X_TELEGRAM_REVIEWER_USER_IDS',
    'TRADE_X_TARGET_USER_ID', 'X_API_KEY', 'X_API_SECRET',
    'X_ACCESS_TOKEN', 'X_ACCESS_TOKEN_SECRET',
}
TOKENS = {'TRADE_TRADING_OPERATOR_TOKEN', 'TRADE_X_ADMIN_TOKEN'}

def write_private(directory, prefix, content):
    fd, name = tempfile.mkstemp(dir=directory, prefix=prefix)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
    except Exception:
        os.unlink(name)
        raise
    return Path(name)

def main():
    os.umask(0o077)
    encoded = sys.stdin.buffer.read(131073).strip()
    if len(encoded) > 131072:
        raise ValueError()
    patch = json.loads(base64.b64decode(encoded, validate=True).decode('utf-8'))
    if not isinstance(patch, dict) or set(patch) != KEYS:
        raise ValueError()
    for value in patch.values():
        if not isinstance(value, str) or not value.strip() or len(value) > 8192:
            raise ValueError()
        if any(char in value for char in '\r\n\x00'):
            raise ValueError()
    directory = Path(sys.argv[2]) / 'deploy'
    target = directory / 'backend.env'
    if target.is_symlink():
        raise ValueError()
    source = target if target.exists() else directory / 'backend.env.example'
    original = source.read_text(encoding='utf-8-sig')
    lines, seen = [], set()
    for line in original.splitlines():
        match = re.match(r'^[ \t]*([A-Z][A-Z0-9_]*)[ \t]*=(.*)$', line)
        if match and match[1] in KEYS | TOKENS:
            key, value = match[1], match[2]
            if key in seen:
                raise ValueError()
            seen.add(key)
            line = key + '=' + (patch[key] if key in KEYS else value or secrets.token_hex(24))
        lines.append(line)
    for key in sorted((KEYS | TOKENS) - seen):
        lines.append(key + '=' + (patch[key] if key in KEYS else secrets.token_hex(24)))
    staged = write_private(directory, '.backend-sync-', '\n'.join(lines) + '\n')
    try:
        if target.exists():
            write_private(directory, 'backend.env.before-sync-', original)
        os.replace(staged, target)
    finally:
        if staged.exists():
            staged.unlink()
    print('Synced 9 settings to deploy/backend.env (mode 600).')
    print('Existing management tokens and business switches were preserved.')

try:
    main()
except Exception:
    print('Config sync failed. Check server path, permissions and backend.env format.', file=sys.stderr)
    sys.exit(1)
'@
}

function Invoke-BackendConfigSync {
    $root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
    $patch = Get-BackendConfigPatch -YamlPath (Join-Path $root 'backend/src/main/resources/application.yml')
    Write-Host ('Ready: ' + ($patch.Keys -join ', '))
    if ($CheckOnly) { Write-Host 'Local check passed. No upload performed.'; return }
    if (-not $Server) { $Server = Read-Host 'Domestic server SSH address (ubuntu@IP)' }
    if ($Server -notmatch '^[A-Za-z0-9_][A-Za-z0-9_.-]*@[A-Za-z0-9][A-Za-z0-9_.-]*$') {
        throw 'Server must be user@hostname or user@IPv4.'
    }
    if ($RemoteRoot -notmatch '^/[A-Za-z0-9_./-]+$' -or $RemoteRoot -match '(^|/)\.\.(/|$)') {
        throw 'RemoteRoot must be a simple absolute Linux path.'
    }
    $ssh = (Get-Command ssh.exe -CommandType Application -ErrorAction Stop).Source
    $arguments = @('-T', '-p', "$Port", '-o', 'StrictHostKeyChecking=yes')
    if ($IdentityFile) {
        if (-not (Test-Path -LiteralPath $IdentityFile -PathType Leaf)) { throw 'IdentityFile not found.' }
        $arguments += @('-i', [IO.Path]::GetFullPath($IdentityFile))
    }
    $code64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes((Get-RemoteConfigWriter)))
    $remoteCommand = "python3 -c 'import base64,sys;exec(base64.b64decode(sys.argv[1]))' $code64 '$RemoteRoot'"
    $arguments += @($Server, $remoteCommand)
    $payload64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($patch | ConvertTo-Json -Compress)))
    # Base64 makes this pipe ASCII-safe in Windows PowerShell 5.1 as well as 7.
    $payload64 | & $ssh @arguments
    if ($LASTEXITCODE -ne 0) { throw 'SSH/config sync failed. No services were restarted.' }
    Write-Host 'Upload complete. Business services were not started or restarted.'
}

# Dot-sourcing only defines helpers, allowing offline tests with synthetic values.
if ($MyInvocation.InvocationName -ne '.') {
    try { Invoke-BackendConfigSync }
    catch { Write-Error -Message $_.Exception.Message -ErrorAction Continue; exit 1 }
}
