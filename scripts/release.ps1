<#
    سكربت الإصدار الواحد لتطبيق Lord TTS — يرفع الترقيم تلقائياً من git
    (بدون لمس يدوي للـ versionCode/versionName)، يبني Release APK، يلتزم
    الترقيم، يضع الوسم vN، يدفع، وينشئ Release على GitHub بمرفق الـ APK.

    الاستخدام (من جذر المستودع):
        .\scripts\release.ps1            # ينفّذ الإصدار كاملاً
        .\scripts\release.ps1 -DryRun    # يعرض الخطة دون أي تغيير

    القواعد الملتزمة بالمشروع:
        - بناء Release فقط (assembleRelease) + الاختبارات قبل أي commit.
        - app_name ثابت "Lord TTS"، والوسم الأحادي vN → 0.N.0
          (متوافق مع alignZeroRelease في UpdateChecker).
#>
[CmdletBinding()]
param(
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

# جذر المستودع = أعلى مجلد السكربت.
$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$gradleFile = Join-Path $repoRoot 'app\build.gradle.kts'
$apkFile = Join-Path $repoRoot 'app\build\outputs\apk\release\lord_tts.apk'

function Invoke-Git {
    param([Parameter(Mandatory = $true)][string[]]$GitArgs)
    $out = & git @GitArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "فشل git $($GitArgs -join ' '): $(($out | Out-String).Trim())"
    }
    return $out
}

# أعلى وسم vN على الـ remote (عبر ls-remote)، وهي المرجع الأوثق حتى مع
# استنساخ جديد أو غياب الأوسمة محلياً.
function Get-RemoteTagMax {
    $out = & git ls-remote --tags origin 2>&1
    if ($LASTEXITCODE -ne 0) {
        return $null
    }
    $maxN = $null
    foreach ($line in $out) {
        if ($line -match 'refs/tags/v(\d+)$') {
            $n = [int]$matches[1]
            if ($null -eq $maxN -or $n -gt $maxN) {
                $maxN = $n
            }
        }
    }
    return $maxN
}

# fallback: آخر وسم واصِل من HEAD محلياً.
$previousEf = $ErrorActionPreference
$ErrorActionPreference = 'SilentlyContinue'
try {
    $describeOut = & git describe --tags --abbrev=0 2>$null
    $describeOk = ($LASTEXITCODE -eq 0)
} finally {
    $ErrorActionPreference = $previousEf
}

$remoteMax = Get-RemoteTagMax
if ($null -ne $remoteMax) {
    $latestTagN = [int]$remoteMax
} elseif ($describeOk -and ($describeOut | Out-String).Trim() -match '^v(\d+)$') {
    $latestTagN = [int]$matches[1]
} else {
    $latestTagN = 0
}

# versionCode الحالي في build.gradle.kts.
$gradleRaw = [System.IO.File]::ReadAllText($gradleFile)
$codeMatch = [regex]::Match($gradleRaw, 'versionCode = (\d+)')
if (-not $codeMatch.Success) {
    throw "لم يُعثر على 'versionCode = ' في $gradleFile"
}
$currentCode = [int]$codeMatch.Groups[1].Value

# الإصدار المستهدف: إن كان الترقيم في الملف سابقاً للوسم الأحدث (أو مطابقاً
# له) نرفعه بمقدار واحد، وإلا ننشر ما هو معلّق فعلاً في الملف.
if ($currentCode -le $latestTagN) {
    $targetCode = $latestTagN + 1
} else {
    $targetCode = $currentCode
}
$targetVersion = "0.$targetCode.0"
$targetTag = "v$targetCode"

# منع النشر المتكرر لنفس الإصدار (محلياً وعلى الـ remote).
& git rev-parse --verify --quiet "refs/tags/$targetTag" 2>$null
if ($LASTEXITCODE -eq 0) {
    throw "الوسم $targetTag موجود محلياً — لا نشر متكرر."
}
$remoteHas = $null
$previousEf2 = $ErrorActionPreference
$ErrorActionPreference = 'SilentlyContinue'
try {
    $remoteHas = & git ls-remote --tags origin 2>$null |
        Select-String -Pattern "refs/tags/$targetTag`$"
} finally {
    $ErrorActionPreference = $previousEf2
}
if ($null -ne $remoteHas) {
    throw "الوسم $targetTag موجود على الـ remote — لا نشر متكرر."
}

Write-Host "=> الإصدار المستهدف: $targetVersion  (وسم $targetTag، versionCode $targetCode)"

if ($DryRun) {
    Write-Host '=> وضع المحاكاة (DryRun): لا تغيير على أي ملف أو remote.'
    if ($currentCode -ne $targetCode) {
        Write-Host "   - تعديل $gradleFile إلى versionCode=$targetCode"
        Write-Host "     و versionName=$targetVersion"
    } else {
        Write-Host "   - $gradleFile لا يحتاج تعديل ترقيم (النسخة معلّقة مسبقاً)."
    }
    Write-Host '   - تشغيل :app:testDebugUnitTest ثم :app:assembleRelease'
    Write-Host '   - commit: app/build.gradle.kts فقط (رسالة عربية)'
    Write-Host "   - tag $targetTag ثم push origin master --tags"
    Write-Host "   - gh release create $targetTag (يرفع lord_tts.apk)"
    exit 0
}

# 1) رفع الترقيم في build.gradle.kts (بلا BOM حفاظاً على DSL).
$newRaw = $gradleRaw `
    -replace 'versionCode = \d+', "versionCode = $targetCode" `
    -replace 'versionName = "\d+\.\d+\.\d+"', "versionName = `"$targetVersion`""
if ($newRaw -ceq $gradleRaw) {
    throw "الترقيم في ملف $gradleFile لا يطابق النمط المتوقع — ألغي الإصدار."
}
[System.IO.File]::WriteAllText(
    $gradleFile, $newRaw, [System.Text.UTF8Encoding]::new($false)
)
Write-Host "=> رُفع الترقيم إلى $targetVersion (versionCode $targetCode)"

# 2) الاختبارات ثم البناء — أي فشل يلغي الإصدار.
Push-Location $repoRoot
try {
    & .\gradlew.bat :app:testDebugUnitTest --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw 'فشلت الاختبارات — أُلغيت عملية الإصدار.'
    }
    & .\gradlew.bat :app:assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw 'فشل بناء Release — أُلغيت عملية الإصدار.'
    }
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $apkFile)) {
    throw "الـ APK غير موجود بعد البناء: $apkFile"
}

# 3) التزام الترقيم فقط (لا تُضاف أي ملفات عمل أخرى).
Invoke-Git @('add', 'app/build.gradle.kts')
Invoke-Git @(
    'commit', '-m', "إصدار v$targetCode — رفع الترقيم الآلي إلى $targetVersion"
)

# 4) الوسم ثم الدفع (الفرع والوسم معاً).
Invoke-Git @('tag', $targetTag)
Invoke-Git @('push', 'origin', 'master')
Invoke-Git @('push', 'origin', $targetTag)

# 5) إنشاء Release على GitHub مع مرفق الـ APK.
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Write-Warning 'gh غير مثبت/موثّق — الدفع والوسم تمّا.'
    Write-Warning "أنشئ الـ Release يدوياً: gh release create $targetTag $apkFile"
    Write-Warning "    --title `"Lord TTS $targetVersion`" --generate-notes"
    exit 0
}
Write-Host "=> إنشاء Release $targetTag على GitHub..."
$ghOut = & gh release create $targetTag $apkFile `
    --repo 'aymankhattab41/Nateq' `
    --title "Lord TTS $targetVersion" `
    --generate-notes 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Warning "فشل gh release create: $(($ghOut | Out-String).Trim())"
    Write-Warning "أعده لاحقاً بـ: gh release create $targetTag $apkFile"
    Write-Warning "    --title `"Lord TTS $targetVersion`" --generate-notes"
} else {
    Write-Host "=> نُشر الإصدار $targetVersion على GitHub ($targetTag)."
    Write-Host "=> الـ APK: $apkFile"
}