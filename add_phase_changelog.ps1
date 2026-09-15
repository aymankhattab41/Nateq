$ErrorActionPreference = "Stop"
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Read-FileU8($path) { return [IO.File]::ReadAllText((Resolve-Path $path).Path, $utf8) }
function Write-FileU8($path, $text) { [IO.File]::WriteAllText((Resolve-Path $path).Path, $text, $utf8) }

$arPath = "feature\settings\src\main\res\values\strings.xml"
$enPath = "feature\settings\src\main\res\values-en\strings.xml"

# البند الجديد (مرحلة مستمرة 16.16 عبر الدفعات)
$arBullet = "• استمرارُ الطور الثابت (16.16) عبر الدفعات المتتالية في [PcmResampler]: الدفعاتُ على النافذة الواحدة بطورٍ مشتركٍ واحد (LongArray(2)) تُنتج تمامَ ناتجِ النداء الواحد على كامل النافذة بلا إعادةِ بدءٍ من الصفر عند حدود الدفعات، فيُزال التقبّطُ (النقراتُ) المسموعُ في الانتقالات"
$enBullet = "• Continuous fixed-phase (16.16) across successive chunks in [PcmResampler]: successive chunks on one continuation window sharing a single phase (LongArray(2)) produce exactly the output of the single call over the entire window without restart-from-zero at chunk boundaries, removing the audible clicks at chunk transitions"

$arAnchor = "الإصدار %1`$s`n"
$enAnchor = "Version %1`$s`n"

$ar = Read-FileU8 $arPath
$en = Read-FileU8 $enPath

if ($ar.Contains($arBullet)) { Write-Output "AR_ALREADY" }
if ($en.Contains($enBullet)) { Write-Output "EN_ALREADY" }

if (-not $ar.Contains($arAnchor)) { throw "AR_ANCHOR_MISSING" }
if (-not $en.Contains($enAnchor)) { throw "EN_ANCHOR_MISSING" }

# إدراج بعد العنوان (أول مكان) فقط — أول حدوث
$arFirstDot = $ar.IndexOf($arAnchor) + $arAnchor.Length
$ar = $ar.Insert($arFirstDot, $arBullet + "`n")
$enFirstDot = $en.IndexOf($enAnchor) + $enAnchor.Length
$en = $en.Insert($enFirstDot, $enBullet + "`n")

# لا تضاعف
if (([regex]::Matches($ar, [regex]::Escape($arBullet))).Count -ne 1) { throw "AR_DUPLICATE" }
if (([regex]::Matches($en, [regex]::Escape($enBullet))).Count -ne 1) { throw "EN_DUPLICATE" }

Write-FileU8 $arPath $ar
Write-FileU8 $enPath $en

# تحقق ما بعد الإدراج
$ar2 = Read-FileU8 $arPath
$en2 = Read-FileU8 $enPath
Write-Output ("AR_OK=" + $ar2.Contains($arBullet))
Write-Output ("EN_OK=" + $en2.Contains($enBullet))
Write-Output ("AR_ONCE=" + (([regex]::Matches($ar2, [regex]::Escape($arBullet))).Count -eq 1))
Write-Output ("EN_ONCE=" + (([regex]::Matches($en2, [regex]::Escape($enBullet))).Count -eq 1))
Write-Output ("AR_KEEPS_OLD_101=" + $ar2.Contains("نطق صحيح لمضاعفات الآلاف 101 و102"))
Write-Output ("EN_KEEPS_OLD_101=" + $en2.Contains("Correct thousands phrasing for 101 and 102"))
