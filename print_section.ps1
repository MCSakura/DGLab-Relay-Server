# =====================================================================
#  print_section.ps1  v1.0
#  Helper for 启动.bat - renders one named section from messages_zh.txt
#  Usage:  powershell.exe -File print_section.ps1  <msgfile_path>  <SECTION_NAME>
#
#  The messages file uses plain UTF-8 (no BOM) and is organised as:
#      ## SECTION_NAME_1
#      line 1
#      line 2
#      ## SECTION_NAME_2
#      ...
#  This script reads the file, splits on "^## " headers, finds the block
#  whose header matches the requested SECTION_NAME (case-sensitive, exact
#  match on the first line of the block), skips the header line of that
#  block and writes every remaining line to the host console via
#  Write-Host.  Write-Host is used intentionally so the output goes
#  straight to the console buffer (not through stdout text pipes) which
#  means CMD.exe's console host renders the UTF-8 Chinese glyphs
#  correctly as long as `chcp 65001` was active in the parent CMD.
# =====================================================================

param(
    [Parameter(Mandatory = $true)]
    [string]$MsgFile,

    [Parameter(Mandatory = $true)]
    [string]$SectionName
)

$ErrorActionPreference = 'Stop'

# --- Validate input file -------------------------------------------------
if (-not (Test-Path -LiteralPath $MsgFile -PathType Leaf)) {
    Write-Host ""
    Write-Host "[FATAL] print_section.ps1: messages file not found: $MsgFile"
    Write-Host ""
    exit 1
}

# --- Read the whole file as UTF-8 (no-BOM safe with new UTF8Encoding($false))
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
$rawText = [System.IO.File]::ReadAllText($MsgFile, $utf8NoBom)

# --- Split into blocks on lines that start with "## " --------------------
# Multiline/ignore-case regex split, but section match is exact below.
$blocks = [regex]::Split($rawText, '(?mi)^##\s+')

$secPat = [regex]::Escape($SectionName)

foreach ($blk in $blocks) {
    # Each block is: "SECTION_NAME\r?\n<content>..."
    # First line must be an exact match for the requested section name
    # followed immediately by a newline.
    if ($blk -match "^$secPat\r?\n") {
        # Split block content into lines, skip the section-name header row
        $lines = [regex]::Split($blk, '\r?\n')
        $afterHeader = $false
        foreach ($ln in $lines) {
            if (-not $afterHeader) {
                $afterHeader = $true
                continue        # drop the "SECTION_NAME" header line itself
            }
            Write-Host $ln
        }
        exit 0
    }
}

# If we reach here, the requested section was not found.  This is a
# programming error (BAT called us with a bad section name) - be loud
# so the maintainer notices instead of silently printing nothing.
Write-Host ""
Write-Host "[FATAL] print_section.ps1: section '$SectionName' was not found in: $MsgFile"
Write-Host ""
exit 2
