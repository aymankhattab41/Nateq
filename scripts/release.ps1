<#
    سكربت الإصدار الواحد لتطبيق Lord TTS — يرفع الترقيم تلقائياً من git
    (بدون لمس يدوي للـ versionCode/versionName)، يبني Release APK، يلتزم
    الترقيم، يضع الوسم vX.Y.Z، يدفع، وينشئ Release على GitHub بمرفق
    الـ APK وملاحظاتِ المستجدات من changelog_text داخل التطبيق (لا توليد
    آلي).

    الاستخدام (من جذر المستودع):
        .\scripts\release.ps1            # ينفّذ الإصدار كاملاً
        .\scripts\release.ps1 -DryRun    # يعرض الخطة دون أي تغيير

    القواعد الملتزمة بالمشروع:
        - بناء Release فقط (assembleRelease) + الاختبارات قبل أي commit.
        - app_name ثابت "Lord TTS".
        - **الترقيم دلاليّ SemVer** بأوسمة `vX.Y.Z`: أول إصدار بعد
          التحويل من الأوسمة الأحادية القديمة `vN` هو `v1.6.1` ثم
          `v1.6.2` وهكذا (كل إصدار = أحدث إصدار منشور + واحد على
          الجزء Patch).
        - versionCode مشتقٌّ من النسخة (major*10000 + minor*100 +
          patch) فيبقى تصاعدياً دائماً ومفهوماً بلا جدول موازٍ.
        - لا إصدار بلا التزامات جديدة واصلة من آخر وسم إلى HEAD
          (منع الإصدار الفارغ).
#>
[CmdletBinding()]
param(
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

# جذر المستودع = أعلى مجلد السكربت.
$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$gradleFile = Join-Path $repoRoot 'app\build.gradle.kts'
$apkFile = Join-Path $repoRoot 'app\build\outputs\apk\release\nateq.apk'

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

# كل أوسمة الـ remote (عبر ls-remote)، وهي المرجع الأوثق حتى مع
# استنساخ جديد أو غياب الأوسمة محلياً. تُرجع مصفوفة أوسمة بلا
# البادئة «v»: إمّا أحادية (`'106'`) من مرحلة ما قبل SemVer، وإما
# ثلاثية (`'1.6.1'`).
function Get-RemoteTags {
    $previousEf = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & git ls-remote --tags origin 2>&1 | ForEach-Object { "$_" }
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousEf
    }
    if ($exitCode -ne 0) {
        return @()
    }
    $tags = New-Object System.Collections.Generic.List[string]
    foreach ($line in $out) {
        # نمط يقبل الصيغتين: أحادية (`v106` من مرحلة ما قبل SemVer)
        # وثلاثية (`v1.6.1`) — مكوّنات رقمية فقط (صفر فواصل أو واحد
        # أو اثنان).
        if ($line -match 'refs/tags/v(\d+(?:\.\d+){0,2})$') {
            $tags.Add($matches[1])
        }
    }
    return $tags.ToArray()
}

# ترتيب ثلاثي على نصّ نسخة بلا بادئة — المقارنة عددية لكل مكوّن
# (الترتيب المعجمي خاطئ: «10» أصغر من «9» نصياً).
function Compare-Version {
    param(
        [string]$Left,
        [string]$Right
    )
    $l = @($Left.Split('.') | ForEach-Object { [int]$_ })
    $r = @($Right.Split('.') | ForEach-Object { [int]$_ })
    $max = [Math]::Max($l.Count, $r.Count)
    for ($i = 0; $i -lt $max; $i++) {
        $lv = if ($i -lt $l.Count) { $l[$i] } else { 0 }
        $rv = if ($i -lt $r.Count) { $r[$i] } else { 0 }
        if ($lv -gt $rv) { return 1 }
        if ($lv -lt $rv) { return -1 }
    }
    return 0
}

# newest وسم منشور: يُرجع كائناً يحمل **النسخة المُطبَّعة** (للحساب
# العددي) و**اسم الوسم الحقيقي** (لـ rev-list). الفصل بينهما جوهري:
# الأحادي القديم «v106» يُحسب «0.106.0» فيرتفع رقمه، لكن `rev-list
# v0.106.0..HEAD` يفشل fatalاً لأن الاسم المنشور هو «v106» — فضيع
# عدّ الالتزامات ويسقط الإصدار. فتبقى الحالة الاثنتان معاً.
function ConvertTo-NormalizedVersion {
    param([string]$Tag)
    $parts = @($Tag.Split('.'))
    if ($parts.Count -eq 1) {
        return "0.$Tag.0"
    }
    if ($parts.Count -eq 2) {
        return "$Tag.0"
    }
    return $Tag
}

function Get-LatestRemoteTag {
    $tags = @(Get-RemoteTags)
    $bestNormalized = $null
    $bestRaw = $null
    foreach ($tag in $tags) {
        $normalized = ConvertTo-NormalizedVersion $tag
        if ($null -eq $bestNormalized -or
            (Compare-Version $normalized $bestNormalized) -gt 0) {
            $bestNormalized = $normalized
            $bestRaw = $tag
        }
    }
    if ($null -eq $bestRaw) {
        return $null
    }
    return [PSCustomObject]@{
        Version = $bestNormalized
        Tag     = "v$bestRaw"
    }
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

# أحدث وسم منشور: مرجع الـ remote أولاً، ثم آخر وسم محلي واصل من
# HEAD (fallback عند غياب الشبكة). الاثنتان منفصلتان دائماً:
# $latestVersion للنسخة المُطبَّعة الثلاثية، و$latestTag للاسم الحقيقي.
$latestRemote = Get-LatestRemoteTag
$latestVersion = if ($null -ne $latestRemote) { $latestRemote.Version } else { $null }
$latestTag = if ($null -ne $latestRemote) { $latestRemote.Tag } else { $null }
if ($null -eq $latestVersion -and
    $describeOk -and
    ($describeOut | Out-String).Trim() -match '^v(\d+(?:\.\d+){0,2})$'
) {
    $localTag = $matches[1]
    $latestVersion = ConvertTo-NormalizedVersion $localTag
    $latestTag = "v$localTag"
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
if ($null -ne $latestTag) {
    $commitOut = Invoke-Git @(
        'rev-list', '--count', "$latestTag..HEAD"
    )
    $sinceTag = $latestTag
} else {
    # أول إصدار في المستودع: كل تاريخ master يُعدّ جديداً.
    $commitOut = Invoke-Git @('rev-list', '--count', 'HEAD')
    $sinceTag = '(لا وسوم بعد)'
}
$newCommitCount = [int]($commitOut | Out-String).Trim()
if ($newCommitCount -lt 1) {
    throw "لا التزامات جديدة منذ $sinceTag — لا حاجة لإصدار."
}

# الرفع التلقائي الإجباري: نسخة كل دفع = أحدث وسم منشور + واحد على
# الجزء Patch (المرجع الـ remote دائماً، ولا حالة «معلّقة» تُفقد
# إصدارها) — فيعمل التحققُ من التحديثات تلقائياً مع كل إصدار جديد.
# **نقطة التحويل من الأوسمة الأحادية:** آخر وسم قديم «0.106.0»
# يُتبع بإصدار «1.6.1» (بداية ترقيم SemVer المطلوب)، ثم «1.6.2»
# ف «1.6.3»… بزيادة Patch وحده.
$targetMajor = 1
$targetMinor = 6
$targetPatch = 1
if ($null -ne $latestVersion) {
    $latestParts = @($latestVersion.Split('.') | ForEach-Object { [int]$_ })
    if ($latestParts[0] -ne 0) {
        $targetMajor = $latestParts[0]
        $targetMinor = $latestParts[1]
        $targetPatch = $latestParts[2] + 1
    }
}
$targetVersion = "$targetMajor.$targetMinor.$targetPatch"
$targetTag = "v$targetVersion"

# versionCode مشتقٌّ من النسخة (major*10000 + minor*100 + patch) — يكبر
# دائماً مع كل إصدار (1.6.1 = 10601) فلا يتعارض مع 0.106.0 = 106،
# ويبقى مفهوماً بلا جدول موازٍ.
$targetCode = ($targetMajor * 10000) + ($targetMinor * 100) + $targetPatch
# **حالة الإصدار المعلّق (resume):** إن كان الملفُ يحمل هذا الترقيمَ
# أصلاً فمُحركُ الإصدار سبق أن رفعه ثم توقّف قبل النشر (انقطاعٌ في
# الشبكة أو فشلُ الاختبارات) — فيُسمح بالمتابعة. أما **تراجعٌ حقيقي**
# (ترقيمٌ أقدم من المنشور) فهو محظورٌ بكل الحالات.
# منعُ النشر المكرر لا يعتمد هذا الحارسَ بل تحقّقُ الوسم أدناه،
# فهو الأدقّ: الوسمُ الموضوعُ على الـ remote هو حقيقةُ النشر.
if ($targetCode -lt $currentCode) {
    throw ("ترقيم النسخة $targetVersion (versionCode $targetCode) أقدم من " +
        "الموجود في الملف (versionCode $currentCode) — تراجعٌ محظور. " +
        "استعدْ الترقيم أو استخدم وسمَ الإصدار كمرجع.")
}

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

# فحص نظافة شجرة العمل قبل النشر (منع حزم تعديلات محلية غير ملتزمة داخل الـ APK)
    $dirty = & git status --porcelain | Where-Object { $_ -match '^[MADRC]' }
    if ($dirty) {
    throw "توجد ملفات معدلة غير ملتزمة في شجرة العمل. التزم كافة التغييرات قبل النشر."
}

Write-Host "=> الإصدار المستهدف: $targetVersion  (وسم $targetTag، versionCode $targetCode)"
Write-Host "=> يغطي $newCommitCount التزاماً جديداً منذ $sinceTag"

# ملاحظات الإصدار من بند المستجدات داخل التطبيق (عربي)، لا توليد آلي.
$changelogXml = Join-Path $repoRoot 'feature\settings\src\main\res\values\strings.xml'
$installNote =
    "`r`n`r`n## التثبيت`r`nنزّل ``nateq.apk`` من مرفقات هذا الإصدار."
if (Test-Path -LiteralPath $changelogXml) {
    $settingsDoc = New-Object System.Xml.XmlDocument
    $settingsDoc.Load($changelogXml)
    $changelogNode =
        $settingsDoc.SelectSingleNode("//string[@name='changelog_text']")
    $rawText = ($changelogNode.InnerText `
        -replace '\\n', "`r`n" `
        -replace '%1\$s', $targetVersion).Trim("`r", "`n")
    # استخراج بنود التحديثات الفردية واقتصارها على أحدث 5 بنود فقط بدلاً من السجل التاريخي الكامل
    $bullets = @([regex]::Split($rawText, '(?m)^\s*\u2022\s*|\u2022\s*') |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and $_ -notmatch '^\s*الإصدار' })
    $maxItems = [Math]::Min(5, $bullets.Count)
    $recentBullets = for ($i = 0; $i -lt $maxItems; $i++) {
        "• " + $bullets[$i].Trim()
    }
    $changelogBody = $recentBullets -join "`r`n"
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
    Write-Host "   - gh release create $targetTag (يرفع nateq.apk" +
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

# ===== حساب توقيع التوقيع (SHA-256) من الـ keystore وتحديث AppIntegrity.kt =====
Write-Host "=> حساب توقيع Release keystore SHA-256..."
try {
    $keyPropsFile = Join-Path $repoRoot 'key.properties'
    if (Test-Path -LiteralPath $keyPropsFile) {
        $keyProps = @{}
        Get-Content -LiteralPath $keyPropsFile | ForEach-Object {
            if ($_ -match '^([^=]+)=(.*)$') {
                $keyProps[$matches[1]] = $matches[2]
            }
        }
        $storeFile = $keyProps['storeFile']
        $storePassword = $keyProps['storePassword']
        $keyAlias = $keyProps['keyAlias']
        $keyPassword = $keyProps['keyPassword']

        if ($storeFile -and $storePassword -and $keyAlias -and $keyPassword) {
            $fullStorePath = Join-Path $repoRoot $storeFile
            if (Test-Path -LiteralPath $fullStorePath) {
                # استخراج الشهادة من الـ keystore وحساب SHA-256
                $certBytes = & keytool -exportcert -keystore $fullStorePath `
                    -storepass $storePassword -keypass $keyPassword `
                    -alias $keyAlias -rfc 2>$null
                if ($LASTEXITCODE -eq 0 -and $certBytes) {
                    # تحويل PEM إلى بايتات وحساب SHA-256
                    $pem = $certBytes -join "`n"
                    $cert = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
                        [System.Text.Encoding]::UTF8.GetBytes($pem)
                    )
                    $sha256 = $cert.GetCertHashString([System.Security.Cryptography.HashAlgorithmName]::SHA256)
                    # تنسيق بالـ uppercase مفصول بنقطتين
                    $formattedSig = ($sha256 -replace '(.{2})', '$1:').TrimEnd(':')
                    Write-Host "=> توقيع Release: $formattedSig"

                    # تحديث AppIntegrity.kt
                    $integrityFile = Join-Path $repoRoot 'app\src\main\java\com\aymankhattab\nateq\AppIntegrity.kt'
                    if (Test-Path -LiteralPath $integrityFile) {
                        $content = [System.IO.File]::ReadAllText($integrityFile, [System.Text.UTF8Encoding]::new($false))
                        $newContent = $content -replace 'REPLACE_WITH_RELEASE_SIGNATURE_SHA256', $formattedSig
                        if ($newContent -cne $content) {
                            [System.IO.File]::WriteAllText($integrityFile, $newContent, [System.Text.UTF8Encoding]::new($false))
                            Write-Host "=> تم تحديث AppIntegrity.kt بالتوقيع الفعلي"
                        }
                    }
                } else {
                    Write-Warning "فشل استخراج الشهادة من keystore (keytool exit code: $LASTEXITCODE)"
                }
            } else {
                Write-Warning "ملف keystore غير موجود: $fullStorePath"
            }
        } else {
            Write-Warning "معلومات keystore ناقصة في key.properties"
        }
    } else {
        Write-Warning "ملف key.properties غير موجود — تخطيت تحديث التوقيع"
    }
} catch {
    Write-Warning "خطأ أثناء حساب/تحديث التوقيع: $_"
}

# **بند 5.3:** سطر SHA-256 للـ APK يُلحق بملاحظات الإصدار — يُحسب من
# القرص مباشرةً بمجرد وجود الملف (بلا نسخ مؤقتة) ويتيح التحققَ من سلامة
# المرفق قبل التثبيت.
$sha256Line = (
    Get-FileHash -LiteralPath $apkFile -Algorithm SHA256
).Hash.ToLowerInvariant()
$releaseNotes = $releaseNotes + "`r`n`r`n### المجموع الاختباري SHA-256`r`n" +
    "``$sha256Line``  nateq.apk"

# 3) التصريح بملف الترقيم ثم الالتزام به فقط — حتى لا تنجرف أي تغييرات أخرى
# مرحّلة أو غير مرحّلة في commit الإصدار. إن كان الترقيم مطبّقاً مسبقاً
# (مرحلة يدوية سابقة) فلا نحاول commit فارغ يفشل.
Invoke-Git @('add', 'app/build.gradle.kts')
$hasStaged = $true
$prevEf2 = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & git diff --cached --quiet 2>$null
    $hasStaged = ($LASTEXITCODE -ne 0)
} finally {
    $ErrorActionPreference = $prevEf2
}
if ($hasStaged) {
    Invoke-Git @(
        'commit', '-m', "إصدار $targetTag — رفع الترقيم الآلي إلى $targetVersion",
        '--', 'app/build.gradle.kts'
    )
} else {
    Write-Host "=> لا تغييرات مرحّلة في app/build.gradle.kts — تخطي commit الترقيم."
}

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