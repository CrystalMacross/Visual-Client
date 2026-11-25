param([string]$In, [string]$Out, [string]$From, [string]$To)
# Windows port of tools/vc-1.20.9-patch/build.sh: compiles AuroraSkyModule for all eight builds and swaps it into the bundle.
$ErrorActionPreference = 'Stop'
$s = 'C:\Users\katja\AppData\Local\Temp\claude\5dea4db50464480d\9707b0d6-2b91-449c-9762-93e3291c3ace\scratchpad'
$o = 'C:\Users\katja\AppData\Local\Temp\claude\7306b69f31b6cfde\838a2301-6b0f-4d7a-a007-b4c068aef357\scratchpad'
$j21 = "$o\tools\jdk\jdk-21.0.13.1+2\bin"; $j25 = "$o\tools\jdk25\jdk-25.0.5.1+2\bin"
$tr = "$o\tools\tiny-remapper.jar"; $asm = Get-Content "$o\tools\asmcp.txt"
$mc = "$s\aurwork\mc"; $src = "$s\aurtools\src\net\visualclient\modules\extra\AuroraSkyModule.java"
$patch = "$s\src-aebd986\tools\vc-1.20.9-patch"
$b = "$s\aurwork\b"; if (Test-Path $b) { Remove-Item -Recurse -Force $b }; New-Item -ItemType Directory -Force $b | Out-Null
& "$j21\javac.exe" -nowarn -d "$b\check" -cp $asm "$patch\LinkCheck.java"
& "$j21\jar.exe" --version | Out-Null
$enc = New-Object System.Text.UTF8Encoding($false)
$code = [IO.File]::ReadAllText($src, $enc)
foreach ($v in '1.21.4', '1.21.5', '1.21.6', '1.21.9', '1.21.11', '26.1', '26.2', '26.3') {
  switch -Wildcard ($v) {
    '1.21.11' { $GG = 'net.minecraft.client.gui.GuiGraphics'; $CAM = 'getMainCamera()'; $YAW = 'yRot()'; $PITCH = 'xRot()'; $TIME = 'level.getDayTime()'; $POS = 'position()'; break }
    '1.21.*' { $GG = 'net.minecraft.client.gui.GuiGraphics'; $CAM = 'getMainCamera()'; $YAW = 'getYRot()'; $PITCH = 'getXRot()'; $TIME = 'level.getDayTime()'; $POS = 'getPosition()'; break }
    '26.1' { $GG = 'net.minecraft.client.gui.GuiGraphicsExtractor'; $CAM = 'getMainCamera()'; $YAW = 'yRot()'; $PITCH = 'xRot()'; $TIME = 'level.getOverworldClockTime()'; $POS = 'position()'; break }
    '26.[23]' { $GG = 'net.minecraft.client.gui.GuiGraphicsExtractor'; $CAM = 'mainCamera()'; $YAW = 'yRot()'; $PITCH = 'xRot()'; $TIME = 'level.getOverworldClockTime()'; $POS = 'position()'; break }
  }
  $old = $v -eq '1.21.4' -or $v -eq '1.21.5'
  $g = [regex]::Replace($code, '\bGG\b', $GG).Replace('gameRenderer.CAMERA', "gameRenderer.$CAM").Replace('cam.YAW', "cam.$YAW").Replace('cam.PITCH', "cam.$PITCH").Replace('night(TIME)', "night($TIME)").Replace('cam.POS', "cam.$POS")
  if ($old) { $g = $g.Replace('.PUSH()', '.pushPose()').Replace('.SCALE(down)', '.scale(down, down, 1.0F)').Replace('.POP()', '.popPose()') }
  else { $g = $g.Replace('.PUSH()', '.pushMatrix()').Replace('.SCALE(down)', '.scale(down, down)').Replace('.POP()', '.popMatrix()') }
  $n = "visualclient-mc$v"
  $gen = "$b\gen\$v\net\visualclient\modules\extra"; New-Item -ItemType Directory -Force $gen, "$b\mod\$n", "$b\classes\$n" | Out-Null
  [IO.File]::WriteAllText("$gen\AuroraSkyModule.java", $g, $enc)
  Push-Location "$b\mod\$n"; & "$j21\jar.exe" xf $In "META-INF/jars/$n.jar"; & "$j21\jar.exe" xf "META-INF\jars\$n.jar" net/visualclient; Remove-Item -Recurse -Force META-INF; Pop-Location
  $libs = ((Get-ChildItem "$o\mcv\$v\libs", "$o\mcv\$v\fapi" -Filter *.jar).FullName) -join ';'
  if ($v -like '1.*') {
    Push-Location "$b\mod\$n"; & "$j21\jar.exe" cf "$b\mod-$v-int.jar" net; Pop-Location
    & "$j21\java.exe" -jar $tr "$b\mod-$v-int.jar" "$b\mod-$v-named.jar" "$mc\full-$v.tiny" intermediary named "$o\mcv\$v\client-intermediary.jar" *> $null
    & "$j21\javac.exe" --release 21 -nowarn -proc:none -d "$b\named-$v" -cp "$mc\$v-named.jar;$b\mod-$v-named.jar;$libs" "$gen\AuroraSkyModule.java"
    if ($LASTEXITCODE -ne 0) { throw "javac $v" }
    Push-Location "$b\named-$v"; & "$j21\jar.exe" cf "$b\aur-$v-named.jar" net; Pop-Location
    & "$j21\java.exe" -jar $tr "$b\aur-$v-named.jar" "$b\aur-$v-int.jar" "$mc\full-$v.tiny" named intermediary "$mc\$v-named.jar" "$b\mod-$v-named.jar" *> $null
    Push-Location "$b\classes\$n"; & "$j21\jar.exe" xf "$b\aur-$v-int.jar"; if (Test-Path META-INF) { Remove-Item -Recurse -Force META-INF }; Pop-Location
  } else {
    & "$j25\javac.exe" --release 21 -nowarn -proc:none -d "$b\classes\$n" -cp "$o\mcv\$v\client.jar;$b\mod\$n;$libs" "$gen\AuroraSkyModule.java"
    if ($LASTEXITCODE -ne 0) { throw "javac $v" }
  }
  $rt = if ($v -like '1.*') { "$o\mcv\$v\client-intermediary.jar" } else { "$o\mcv\$v\client.jar" }
  $lc = & "$j21\java.exe" -cp "$b\check;$asm" LinkCheck "$b\classes\$n\net\visualclient\modules\extra\AuroraSkyModule.class" "$b\classes\$n" $rt "$b\mod\$n" | Select-Object -Last 1
  "  {0}: {1}" -f $v, $lc
}
& "$j25\java.exe" --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED --add-exports java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED "$patch\Swap.java" $In $Out "$b\classes" $From $To
