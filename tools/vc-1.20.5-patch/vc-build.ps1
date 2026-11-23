# Compiles the changed / new Visual Client classes (firework crash fix, Texture Painter fixes, Pin module)
# for every Minecraft build inside the bundle and checks every reference with LinkCheck + MixinCheck.
$ErrorActionPreference = 'Stop'
$s = 'C:\Users\katja\AppData\Local\Temp\claude\7306b69f31b6cfde\838a2301-6b0f-4d7a-a007-b4c068aef357\scratchpad'
$v = 'C:\Users\katja\AppData\Local\Temp\claude\5dea4db50464480d\f1b86519-aacf-46ec-9dfb-b1532c3c22a6\scratchpad\vc'
$w = "$v\work"
$j21 = "$s\tools\jdk\jdk-21.0.13.1+2\bin"
$j25 = "$s\tools\jdk25\jdk-25.0.5.1+2\bin"
$lib = "$env:APPDATA\ModrinthApp\meta\libraries"
$enc = New-Object System.Text.UTF8Encoding($false)
$nested = "$v\nested"

# 1. 26.x sources: intermediary -> Mojang names (ToMojang) -> 26.x rename rules -> hand-written 26.x files
foreach ($p in @(@('src26-1', @('rename26.txt')), @('src26-2', @('rename26.txt', 'rename26_2.txt')), @('src26-3', @('rename26.txt', 'rename26_2.txt', 'rename26_3.txt')))) {
  $out = "$w\$($p[0])"
  if (Test-Path $out) { Remove-Item -Recurse -Force $out }
  $conv = & "$j25\java.exe" "$s\tools\ToMojang.java" "$s\tools\named.txt" "$w\src-base" $out
  ($conv | Select-Object -Last 1) -replace '^', "  $($p[0]) "
  $rules = @()
  $ruleFiles = @($p[1] | ForEach-Object { "$s\tools\$_" }) + @("$w\rename-extra26.txt")
  if ($p[0] -ne 'src26-1') { $ruleFiles += "$w\rename-extra26_2.txt" }
  foreach ($rf in $ruleFiles) {
    foreach ($line in (Get-Content $rf)) {
      if ($line -and -not $line.StartsWith('#')) { $q = $line -split ' => ', 2; $rules += , @($q[0], $q[1]) }
    }
  }
  Get-ChildItem $out -Recurse -Filter *.java | ForEach-Object {
    $t = [IO.File]::ReadAllText($_.FullName)
    $t = $t.Replace('method_25302', 'blit').Replace('method_70846', 'blitSprite').Replace('method_52706', 'blitSprite')
    foreach ($r in $rules) { $t = [regex]::Replace($t, $r[0], $r[1]) }
    [IO.File]::WriteAllText($_.FullName, $t, $enc)
  }
  Get-ChildItem "$w\src26" -Recurse -Filter *.java | ForEach-Object {
    $dst = Join-Path $out $_.FullName.Substring("$w\src26".Length + 1)
    New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
    Copy-Item $_.FullName $dst -Force
  }
}

# 2. compile per build
$targets = @(
  @('visualclient-mc1.21.4.jar', '1.21.4', 'src-base', $j21, @('1.21.4')),
  @('visualclient-mc1.21.5.jar', '1.21.5', 'src-base', $j21, @('1.21.5')),
  @('visualclient-mc1.21.6.jar', '1.21.6', 'src-base', $j21, @('1.21.6', '1.21.7', '1.21.8')),
  @('visualclient-mc1.21.9.jar', '1.21.9', 'src-base', $j21, @('1.21.9', '1.21.10')),
  @('visualclient-mc1.21.11.jar', '1.21.11', 'src-base', $j21, @('1.21.11')),
  @('visualclient-mc26.1.jar', '26.1', 'src26-1', $j25, @('26.1', '26.1.1', '26.1.2')),
  @('visualclient-mc26.2.jar', '26.2', 'src26-2', $j25, @('26.2')),
  @('visualclient-mc26.3.jar', '26.3', 'src26-3', $j25, @('26.3'))
)
$asm = Get-Content "$s\tools\asmcp.txt"
$fail = $false
foreach ($t in $targets) {
  $jar = $t[0]; $mc = $t[1]; $src = "$w\$($t[2])"; $jdk = $t[3]
  $d = "$s\mcv\$mc"
  $mcJar = if (Test-Path "$d\client-intermediary.jar") { "$d\client-intermediary.jar" } else { "$d\client.jar" }
  $cp = @($mcJar) + (Get-ChildItem "$d\libs" -Filter *.jar).FullName + (Get-ChildItem "$d\fapi" -Filter *.jar -ErrorAction SilentlyContinue).FullName
  $cp += (Get-ChildItem $lib -Recurse -Filter 'fabric-loader-0.19.5.jar').FullName
  $cp += (Get-ChildItem $lib -Recurse -Filter 'sponge-mixin-0.17.0+mixin.0.8.7.jar').FullName
  $cp += "$nested\$jar"
  $out = "$w\out\$mc"
  if (Test-Path $out) { Remove-Item -Recurse -Force $out }
  New-Item -ItemType Directory -Force $out | Out-Null
  $files = Get-ChildItem $src -Recurse -Filter *.java | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
  $argFile = "$w\javac-$mc.args"
  (@('-nowarn', '-encoding', 'UTF-8', '--release', '21', '-proc:none', '-cp', ('"' + (($cp | Where-Object { $_ }) -join ';').Replace('\', '/') + '"'), '-d', ('"' + $out.Replace('\', '/') + '"')) + $files) | Set-Content -Encoding ascii $argFile
  $ErrorActionPreference = 'Continue'
  $log = & "$jdk\javac.exe" "-J-Duser.language=en" "@$argFile" 2>&1 | ForEach-Object { "$_" }
  $ErrorActionPreference = 'Stop'
  $errs = @($log | Where-Object { $_ -match 'error:' })
  $classes = @(Get-ChildItem $out -Recurse -Filter *.class).Count
  $links = foreach ($ver in $t[4]) {
    $vj = if (Test-Path "$s\mcv\$ver\client-intermediary.jar") { "$s\mcv\$ver\client-intermediary.jar" } else { "$s\mcv\$ver\client.jar" }
    if (-not (Test-Path $vj)) { "$ver=no-jar"; continue }
    $r = (& "$j25\java.exe" -cp $asm "$s\tools\LinkCheck.java" $out $vj) -join ' '
    $m = (& "$j25\java.exe" -cp $asm "$s\tools\MixinCheck.java" "$out\net\visualclient\mixin" $vj) -join ' '
    if ($r -notmatch 'LINK OK' -or $m -notmatch 'OK: all mixin targets found') { $script:fail = $true }
    "$ver=" + $(if ($r -match 'LINK OK') { 'link-OK' } else { $r }) + '/' + $(if ($m -match 'OK: all mixin targets found') { 'mixin-OK' } else { $m })
  }
  if ($errs.Count -gt 0) { $fail = $true }
  "{0,-28} javac errors={1} classes={2} {3}" -f $jar, $errs.Count, $classes, ($links -join ' ')
  if ($errs.Count -gt 0) {
    $log | Select-Object -First 40 | ForEach-Object { "    " + ($_ -replace '^.*\\net\\visualclient\\', '') }
  }
}
if ($fail) { 'RESULT: FAILED' } else { 'RESULT: all 8 builds compiled, linked and mixin-checked' }
