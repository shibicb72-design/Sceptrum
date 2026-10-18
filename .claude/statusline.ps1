# Claude Code status line: model | context window used % | 5-hour rate limit used %.
# Reads the status line JSON from stdin (https://code.claude.com/docs/en/statusline).
# Every field may be missing or null; missing values print as "--". Always exits 0.
$ErrorActionPreference = 'SilentlyContinue'

$raw = [Console]::In.ReadToEnd()
$data = $null
if ($raw) { try { $data = $raw | ConvertFrom-Json } catch { $data = $null } }

function Get-Field($obj, [string[]]$path) {
    foreach ($name in $path) {
        if ($null -eq $obj) { return $null }
        $prop = $obj.PSObject.Properties[$name]
        if ($null -eq $prop) { return $null }
        $obj = $prop.Value
    }
    return $obj
}

function Format-Percent($value) {
    if ($null -eq $value -or "$value" -eq '') { return '--' }
    try { return ([Math]::Round([double]$value)).ToString([Globalization.CultureInfo]::InvariantCulture) + '%' } catch { return '--' }
}

$model = Get-Field $data @('model', 'display_name')
if (-not $model) { $model = Get-Field $data @('model', 'id') }
if (-not $model) { $model = '?' }
$context = Get-Field $data @('context_window', 'used_percentage')
$fiveHour = Get-Field $data @('rate_limits', 'five_hour', 'used_percentage')

Write-Output ('[' + $model + '] ctx ' + (Format-Percent $context) + ' | 5h ' + (Format-Percent $fiveHour))
exit 0
