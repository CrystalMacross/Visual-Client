# Builds visual-client-1.20.5.jar from the 1.20.4 bundle: every entry is copied unchanged except the classes
# compiled by vc-build.ps1 (replaced or added per Minecraft build), the pin key in the lang files and the versions.
$ErrorActionPreference = 'Stop'
$v = 'C:\Users\katja\AppData\Local\Temp\claude\5dea4db50464480d\f1b86519-aacf-46ec-9dfb-b1532c3c22a6\scratchpad\vc'
$w = "$v\work"
$from = '1.20.4'; $to = '1.20.5'
$enc = New-Object System.Text.UTF8Encoding($false)
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$outOf = @{ 'visualclient-mc1.21.4.jar' = '1.21.4'; 'visualclient-mc1.21.5.jar' = '1.21.5'; 'visualclient-mc1.21.6.jar' = '1.21.6'; 'visualclient-mc1.21.9.jar' = '1.21.9'; 'visualclient-mc1.21.11.jar' = '1.21.11'; 'visualclient-mc26.1.jar' = '26.1'; 'visualclient-mc26.2.jar' = '26.2'; 'visualclient-mc26.3.jar' = '26.3' }
$lang = @{
  'assets/visualclient/lang/en_us.json' = @('key.visualclient.pin', 'Place pin (Visual Client)')
  'assets/visualclient/lang/de_de.json' = @('key.visualclient.pin', 'Pin setzen (Visual Client)')
}

function Read-Entry($e) { $ms = New-Object IO.MemoryStream; $st = $e.Open(); $st.CopyTo($ms); $st.Close(); return , $ms.ToArray() }
function Write-Entry($zip, $name, [byte[]]$bytes, $time) {
  $level = if ($name.EndsWith('/')) { [System.IO.Compression.CompressionLevel]::NoCompression } else { [System.IO.Compression.CompressionLevel]::Optimal }
  $n = $zip.CreateEntry($name, $level)
  $n.LastWriteTime = $time
  if (-not $name.EndsWith('/')) { $st = $n.Open(); $st.Write($bytes, 0, $bytes.Length); $st.Close() }
}
function Set-Version([byte[]]$json, $pattern, $replacement) {
  $t = $enc.GetString($json)
  $n = [regex]::Replace($t, $pattern, $replacement)
  if ($n -eq $t) { throw "version not found in fabric.mod.json" }
  return , $enc.GetBytes($n)
}
function Add-LangKey([byte[]]$json, $key, $value) {
  $t = $enc.GetString($json)
  if ($t.Contains('"' + $key + '"')) { return , $json }
  $i = $t.LastIndexOf('}')
  $body = $t.Substring(0, $i).TrimEnd()
  $n = $body + ",`n  `"$key`": `"$value`"`n}`n"
  return , $enc.GetBytes($n)
}

$patched = @{}
$report = @()
foreach ($name in $outOf.Keys) {
  $dir = "$w\out\$($outOf[$name])"
  # every compiled class, keyed by its path inside the jar
  $new = @{}
  Get-ChildItem $dir -Recurse -Filter *.class | ForEach-Object { $new[$_.FullName.Substring($dir.Length + 1).Replace('\', '/')] = $_.FullName }
  # outer classes we rebuilt: their old inner classes are dropped unless the new build has them too
  $outer = @{}
  foreach ($k in $new.Keys) { $outer[($k -replace '\$.*$', '' -replace '\.class$', '')] = $true }
  $src = [System.IO.Compression.ZipFile]::OpenRead("$v\nested\$name")
  $ms = New-Object IO.MemoryStream
  $dst = New-Object System.IO.Compression.ZipArchive($ms, [System.IO.Compression.ZipArchiveMode]::Create, $true)
  $seen = @{}; $replaced = 0; $dropped = 0; $time = $null
  foreach ($e in $src.Entries) {
    if ($null -eq $time) { $time = $e.LastWriteTime }
    $full = $e.FullName
    if ($full.EndsWith('.class') -and -not $new.ContainsKey($full) -and $outer.ContainsKey(($full -replace '\$.*$', '' -replace '\.class$', ''))) { $dropped++; continue }
    $bytes = if ($full.EndsWith('/')) { [byte[]]@() } else { Read-Entry $e }
    if ($new.ContainsKey($full)) { $bytes = [IO.File]::ReadAllBytes($new[$full]); $seen[$full] = $true; $replaced++ }
    elseif ($full -eq 'fabric.mod.json') { $bytes = Set-Version $bytes ('"version":\s*"' + [regex]::Escape($from) + '\.') ('"version": "' + $to + '.') }
    elseif ($lang.ContainsKey($full)) { $bytes = Add-LangKey $bytes $lang[$full][0] $lang[$full][1] }
    Write-Entry $dst $full $bytes $e.LastWriteTime
  }
  $added = 0
  foreach ($k in ($new.Keys | Sort-Object)) {
    if (-not $seen.ContainsKey($k)) { Write-Entry $dst $k ([IO.File]::ReadAllBytes($new[$k])) $time; $added++ }
  }
  $dst.Dispose(); $src.Dispose()
  $patched[$name] = $ms.ToArray()
  $report += "  {0,-28} replaced={1} added={2} dropped-old-inner={3}" -f $name, $replaced, $added, $dropped
}
$report

# bundle
$outJar = "$v\visual-client-$to.jar"
if (Test-Path $outJar) { Remove-Item $outJar }
$src = [System.IO.Compression.ZipFile]::OpenRead("$v\in\visual-client-$from.jar")
$dst = [System.IO.Compression.ZipFile]::Open($outJar, [System.IO.Compression.ZipArchiveMode]::Create)
$done = 0
foreach ($e in $src.Entries) {
  $bytes = if ($e.FullName.EndsWith('/')) { [byte[]]@() } else { Read-Entry $e }
  if ($e.FullName -like 'META-INF/jars/*.jar') {
    if (-not $patched.ContainsKey($e.Name)) { throw "unknown build $($e.Name)" }
    $bytes = $patched[$e.Name]; $done++
  } elseif ($e.FullName -eq 'fabric.mod.json') {
    $bytes = Set-Version $bytes ('"version":\s*"' + [regex]::Escape($from) + '"') ('"version": "' + $to + '"')
  }
  Write-Entry $dst $e.FullName $bytes $e.LastWriteTime
}
$dst.Dispose(); $src.Dispose()
if ($done -ne 8) { throw "expected 8 builds, patched $done" }

# verify what was written
$z = [System.IO.Compression.ZipFile]::OpenRead($outJar)
$outerVer = [regex]::Match($enc.GetString((Read-Entry $z.GetEntry('fabric.mod.json'))), '"version":\s*"([^"]+)"').Groups[1].Value
"visual-client-$to.jar: {0:N0} bytes, entries {1}, bundle version {2}" -f (Get-Item $outJar).Length, $z.Entries.Count, $outerVer
$sha = [System.Security.Cryptography.SHA256]::Create()
foreach ($e in ($z.Entries | Where-Object { $_.FullName -like 'META-INF/jars/*.jar' } | Sort-Object Name)) {
  $inner = New-Object System.IO.Compression.ZipArchive((New-Object IO.MemoryStream(, (Read-Entry $e))))
  $ver = [regex]::Match($enc.GetString((Read-Entry $inner.GetEntry('fabric.mod.json'))), '"version":\s*"([^"]+)"').Groups[1].Value
  $dir = "$w\out\$($outOf[$e.Name])"
  $bad = 0
  Get-ChildItem $dir -Recurse -Filter *.class | ForEach-Object {
    $k = $_.FullName.Substring($dir.Length + 1).Replace('\', '/')
    $ie = $inner.GetEntry($k)
    if ($null -eq $ie -or [Convert]::ToBase64String($sha.ComputeHash((Read-Entry $ie))) -ne [Convert]::ToBase64String($sha.ComputeHash([IO.File]::ReadAllBytes($_.FullName)))) { $bad++ }
  }
  $en = $enc.GetString((Read-Entry $inner.GetEntry('assets/visualclient/lang/en_us.json')))
  $null = $en | ConvertFrom-Json
  "  {0,-28} version={1,-12} entries={2,4} classesMatch={3} pinKey={4}" -f $e.Name, $ver, $inner.Entries.Count, ($bad -eq 0), $en.Contains('key.visualclient.pin')
  $inner.Dispose()
}
$z.Dispose()
