<#
    سكربت الإصدار الواحد لتطبيق Lord TTS — يرفع الترقيم تلقائياً من git
    (بدون لمس يدوي للـ versionCode/versionName)، يبني Release APK، يلتزم
    الترقيم، يضع الوسم vN، يدفع، وينشئ Release على GitHub بمرفق الـ APK
    وملاحظاتِ المستجدات من changelog_text داخل التطبيق (لا توليد آلي).

    الاستخدام (من جذر المستودع):
        .\scripts\release.ps1            # ينفّذ الإصدار كاملاً
        .\scripts\release.ps1 -DryRun    # يعرض الخطة دون أي تغيير

    القواعد الملتزمة بالمشروع:
        - بناء Release فقط (assembleRelease) + الاختبارات قبل أي commit.
        - app_name ثابت "Lord TTS"، والوسم الأحادي vN → 0.N.0
          (متوافق مع alignZeroRelease في UpdateChecker).
        - كل دفع = إصدار جديد تلقائياً: النسخة دائماً (أعلى وسم منشور + 1)
          فيعمل التحققُ من التحديثات تلقائياً؛ ولا إصدار بلا التزامات
          جديدة واصلة من آخر وسم إلى HEAD (منع الإصدار الفارغ).
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
    # PowerShell 5.1 يحوّل أي مخرجات stderr (مثل تحذير CRLF أو تقدم push)
    # إلى أخطاء حمراء قاطعة مع ErrorActionPreference=Stop وإن نجح git نفسه.
    # نعيد الضبط مؤقتاً، ونحوّل كل المخرجات إلى نصوص، ونفشل فقط عند كود خروج
    # غير صفري.
    $previousEf = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & git @GitArgs 2>&1 | ForEach-Object { "$_" }
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousEf
    }
    if ($exitCode -ne 0) {
        throw "فشل git $($GitArgs -join ' '): $(($out | Out-String).Trim())"
    }
    return $out
}

# أعلى وسم vN على الـ remote (عبر ls-remote)، وهي المرجع الأوثق حتى مع
# استنساخ جديد أو غياب الأوسمة محلياً.
function Get-RemoteTagMax {
    $previousEf = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & git ls-remote --tags origin 2>&1 | ForEach-Object { "$_" }
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousEf
    }
    if ($exitCode -ne 0) {
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

# versionCode الحالي في build.gradle.kts (عرض/ملف فقط — لا يُرجع).
$gradleRaw = [System.IO.File]::ReadAllText($gradleFile)
$codeMatch = [regex]::Match($gradleRaw, 'versionCode = (\d+)')
if (-not $codeMatch.Success) {
    throw "لم يُعثر على 'versionCode = ' في $gradleFile"
}
$currentCode = [int]$codeMatch.Groups[1].Value

# منع الإصدار الفارغ: لا إصدار إن لم توجد التزامات جديدة واصلة من آخر
# وسم منشور إلى HEAD (لا «دفع بلا مبرر» قبل نشرٍ).
# **بند 9.1:** جلب الأوسمة من الخادم قبل حساب الالتزامات — git rev-list
# يفشل (fatal: ambiguous argument) حين يُشحن المستودع من جهاز آخر أو
# استُنسخ حديثاً والأوسمة غير موجودة محلياً. الفشل هنا لا يُوقف الإصدار:
# يتابع التقدير بالأوسمة المحلية المتاحة.
$previousEf = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & git fetch --tags origin 2>&1 | ForEach-Object { "$_" }
} finally {
    $ErrorActionPreference = $previousEf
}
if ($latestTagN -gt 0) {
    $commitOut = Invoke-Git @('rev-list', '--count', "v$latestTagN..HEAD")
    $sinceTag = "v$latestTagN"
} else {
    # أول إصدار في المستودع: كل تاريخ master يُعدّ جديداً.
    $commitOut = Invoke-Git @('rev-list', '--count', 'HEAD')
    $sinceTag = '(لا وسوم بعد)'
}
$newCommitCount = [int]($commitOut | Out-String).Trim()
if ($newCommitCount -lt 1) {
    throw "لا التزامات جديدة منذ $sinceTag — لا حاجة لإصدار."
}

# الرفع التلقائي الإجباري: نسخة كل دفع = أعلى وسم منشور على الـ remote
# بمقدار واحد (المرجع الـ remote دائماً، ولا حالة «معلّقة» تُفقد إصدارها) —
# فيعمل التحققُ من التحديثات تلقائياً مع كل إصدار جديد.
$targetCode = $latestTagN + 1
$targetVersion = "0.$targetCode.0"
$targetTag = "v$targetCode"

# منع النشر المتكرر لنفس الإصدار (محلياً وعلى الـ remote).
$previousEf = $ErrorActionPreference
$ErrorActionPreference = 'SilentlyContinue'
try {
    & git rev-parse --verify --quiet "refs/tags/$targetTag" 2>$null
    $localHas = ($LASTEXITCODE -eq 0)
} finally {
    $ErrorActionPreference = $previousEf
}
if ($localHas) {
    throw "الوسم $targetTag موجود محلياً — لا نشر متكرر."
}
$remoteHas = Get-RemoteTagMax | Out-Null
$previousEf = $ErrorActionPreference
$ErrorActionPreference = 'SilentlyContinue'
try {
    $remoteHas = & git ls-remote --tags origin 2>$null |
        Select-String -Pattern "refs/tags/$targetTag`$"
} finally {
    $ErrorActionPreference = $previousEf
}
if ($null -ne $remoteHas) {
    throw "الوسم $targetTag موجود على الـ remote — لا نشر متكرر."
}

Write-Host "=> الإصدار المستهدف: $targetVersion  (وسم $targetTag، versionCode $targetCode)"
Write-Host "=> يغطي $newCommitCount التزاماً جديداً منذ $sinceTag"

# ملاحظات الإصدار من بند المستجدات داخل التطبيق (عربي)، لا توليد آلي.
$changelogXml = Join-Path $repoRoot 'feature\settings\src\main\res\values\strings.xml'
$installNote =
    "`r`n`r`n## التثبيت`r`nنزّل ``lord_tts.apk`` من مرفقات هذا الإصدار."
if (Test-Path -LiteralPath $changelogXml) {
    $settingsDoc = New-Object System.Xml.XmlDocument
    $settingsDoc.Load($changelogXml)
    $changelogNode =
        $settingsDoc.SelectSingleNode("//string[@name='changelog_text']")
    $changelogBody =
        ($changelogNode.InnerText `
            -replace '\\n', "`r`n" `
            -replace '%1\$s', $targetVersion).Trim("`r", "`n")
    # يُذكر الإصدار في عنوان الملاحظات — فتُحذف البادئة «الإصدار vN» من النص.
    $intro = "الإصدار $targetVersion"
    if ($changelogBody -like "$intro*") {
        $changelogBody = $changelogBody.Substring($intro.Length).
            TrimStart("`r", "`n")
    }
    $releaseNotes = "Lord TTS $targetVersion`r`n`r`n" +
        "$changelogBody$installNote"
} else {
    $releaseNotes = "Lord TTS $targetVersion`r`n`r`n" +
        "لم يُعثر على changelog_text — راجع بند المستجدات داخل التطبيق."
}

if ($DryRun) {
    Write-Host '=> وضع المحاكاة (DryRun): لا تغيير على أي ملف أو remote.'
    Write-Host "   - رفع $gradleFile إلى versionCode=$targetCode"
    Write-Host "     و versionName=$targetVersion (يغطي $newCommitCount " +
        "commit جديداً منذ $sinceTag)"
    Write-Host '   - تشغيل testDebugUnitTest لكل الوحدات ثم' +
        ' :app:assembleRelease'
    Write-Host '   - commit: app/build.gradle.kts فقط (رسالة عربية)'
    Write-Host "   - tag $targetTag ثم push origin master --tags"
    Write-Host "   - gh release create $targetTag (يرفع lord_tts.apk" +
        ' بملاحظات المستجدات من changelog_text)'
    exit 0
}

# 1) رفع الترقيم في build.gradle.kts (بلا BOM حفاظاً على DSL) — إن لم يكن
# مطبقاً مسبقاً (حالة إصدار معلّق بعد تنفيذ ناقص أو إعادة تشغيل).
$newRaw = $gradleRaw `
    -replace 'versionCode = \d+', "versionCode = $targetCode" `
    -replace 'versionName = "\d+\.\d+\.\d+"', "versionName = `"$targetVersion`""
if ($newRaw -cne $gradleRaw) {
    [System.IO.File]::WriteAllText(
        $gradleFile, $newRaw, [System.Text.UTF8Encoding]::new($false)
    )
    Write-Host "=> رُفع الترقيم إلى $targetVersion (versionCode $targetCode)"
} else {
    Write-Host "=> الترقيم $targetVersion مطبّق مسبقاً — نكمل دون تعديل."
}

# 2) الاختبارات ثم البناء — أي فشل يلغي الإصدار.
# **بند 9.2:** تشغيل اختبارات الوحدة لكافة وحدات المشروع (وليس :app فقط)
# — حتى لا يُنشر إصدار بمحرّك نطق معطوب دون أن تكتشفه الاختبارات.
Push-Location $repoRoot
try {
    & .\gradlew.bat `
        :core:common:testDebugUnitTest `
        :core:engine:testDebugUnitTest `
        :core:audio:testDebugUnitTest `
        :core:data:testDebugUnitTest `
        :feature:settings:testDebugUnitTest `
        :feature:widget:testDebugUnitTest `
        :app:testDebugUnitTest `
        --console=plain
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

# 3) التصريح بملف الترقيم ثم الالتزام به فقط — حتى لا تنجرف أي تغييرات أخرى
# مرحّلة أو غير مرحّلة في commit الإصدار.
Invoke-Git @('add', 'app/build.gradle.kts')
Invoke-Git @(
    'commit', '-m', "إصدار v$targetCode — رفع الترقيم الآلي إلى $targetVersion",
    '--', 'app/build.gradle.kts'
)

# 4) الوسم ثم الدفع (الفرع والوسم معاً).
Invoke-Git @('tag', $targetTag)
Invoke-Git @('push', 'origin', 'master')
Invoke-Git @('push', 'origin', $targetTag)

# 5) إنشاء Release على GitHub مع مرفق الـ APK.
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Write-Warning 'gh غير مثبت/موثّق — الدفع والوسم تمّا.'
    Write-Warning "أنشئ الـ Release يدوياً: gh release create $targetTag $apkFile"
    Write-Warning "    --title `"Lord TTS $targetVersion`" --notes"
    exit 0
}
Write-Host "=> إنشاء Release $targetTag على GitHub..."
$ghOut = & gh release create $targetTag $apkFile `
    --repo 'aymankhattab41/Nateq' `
    --title "Lord TTS $targetVersion" `
    --notes $releaseNotes 2>&1 | ForEach-Object { "$_" }
if ($LASTEXITCODE -ne 0) {
    Write-Warning "فشل gh release create: $(($ghOut | Out-String).Trim())"
    Write-Warning "أعده لاحقاً بـ: gh release create $targetTag $apkFile"
    Write-Warning "    --title `"Lord TTS $targetVersion`" --notes"
} else {
    Write-Host "=> نُشر الإصدار $targetVersion على GitHub ($targetTag)."
    Write-Host "=> الـ APK: $apkFile"
}