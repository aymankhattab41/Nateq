$ErrorActionPreference = "Stop"
$enc = New-Object System.Text.UTF8Encoding($false)
$get = { param($p) [IO.File]::ReadAllText($p, $enc) }
$put = { param($p, $s) [IO.File]::WriteAllText($p, $s, $enc) }

$arPath = "feature\settings\src\main\res\values\strings.xml"
$enPath = "feature\settings\src\main\res\values-en\strings.xml"

$arTitle = "الإصدار %1`$s"
$enTitle = "Version %1`$s"

$arBul1 = "• نطق صحيح لمضاعفات الآلاف 101 و102"
$enBul1 = "• Correct thousands phrasing for 101 and 102"

$newAr = "• استمرار الطور (16.16) عبر الدفعات: دفعاتُ القطعة الواحدة تستأنفُ من موضع الطور المشترك ذاته (لا إعادةَ بدءٍ من الصفر عند حدود الدفعات) فنتيجةُ مجموعها تطابق ناتجَ النداء الواحد على كامل النافذة — لا تقبّطٌ في الانتقالات"
$newEn = "• Continuous phase (16.16) across chunks: consecutive chunks of the same slice resume from the same shared phase (no restart-from-zero at chunk boundaries) so the joined result matches the single call over the whole window — no clicks at the transitions"

$ar = & $get $arPath
$en = & $get $enPath

if (-not $ar.Contains($arTitle)) { throw "AR_TITLE_MISSING" }
if (-not $en.Contains($enTitle)) { throw "EN_TITLE_MISSING" }
if (-not $ar.Contains($arBul1)) { throw "AR_BUL1_MISSING" }
if (-not $en.Contains($enBul1)) { throw "EN_BUL1_MISSING" }

# أدرج البند الجديد بعد العنوان مباشرةً (يسبق البند الأول) في كليهما معاً.
$ar = $ar.Replace($arTitle + "`n" + $arBul1, $arTitle + "`n" + $newAr + "`n" + $arBul1)
$en = $en.Replace($enTitle + "`n" + $enBul1, $enTitle + "`n" + $newEn + "`n" + $enBul1)

& $put $arPath $ar
& $put $enPath $en

$chkAr = & $get $arPath
$chkEn = & $get $enPath
Write-Output ("AR_OK=" + $chkAr.Contains($newAr))
Write-Output ("EN_OK=" + $chkEn.Contains($newEn))
Write-Output ("AR_KEEP=" + $chkAr.Contains($arBul1))
Write-Output ("EN_KEEP=" + $chkEn.Contains($enBul1))
