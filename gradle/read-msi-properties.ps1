# Print every row of an MSI's Property table as `Name=Value`, one line each.
#
# Called from `verifyWindowsInstaller` in build.gradle.kts, which reads UpgradeCode and
# ProductVersion back out of the installer jpackage has just built. An MSI is a compound
# document, not text: the only supported way to ask it for a property is the Windows Installer
# COM automation interface, which is why this is PowerShell and not Kotlin.
#
# The `InvokeMember` calls are not ceremony either — the objects that interface hands back are
# IDispatch-only, so PowerShell cannot bind `.OpenView(...)` or `.StringData(1)` directly and
# reflection is the documented way to reach them.

param(
    [Parameter(Mandatory = $true)][string] $MsiPath
)

$ErrorActionPreference = 'Stop'

$installer = New-Object -ComObject WindowsInstaller.Installer
# Mode 0 = read-only. Any other mode takes a write lock on the artifact we are about to ship.
$database = $installer.GetType().InvokeMember('OpenDatabase', 'InvokeMethod', $null, $installer, @($MsiPath, 0))
$view = $database.GetType().InvokeMember('OpenView', 'InvokeMethod', $null, $database, @('SELECT Property, Value FROM Property'))
$view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, $null)

while ($true) {
    $record = $view.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $view, $null)
    if ($null -eq $record) { break }
    $name = $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, 1)
    $value = $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, 2)
    # Not `Write-Output`: that goes through PowerShell's formatting engine, which reflows plain
    # strings at the output width (120 columns when stdout is redirected, as it is here). A long
    # property — `ARPCOMMENTS`, a description — would arrive at Gradle split across lines, and a
    # continuation containing `=` would parse as a property of its own. Writing to the console
    # stream directly keeps one row on one line whatever its length.
    [Console]::Out.WriteLine("$name=$value")
}

$view.GetType().InvokeMember('Close', 'InvokeMethod', $null, $view, $null)
