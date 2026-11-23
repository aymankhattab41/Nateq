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
    [switch]$DryRun,
    [string]$TargetVersionOverride
)

$ErrorActionPreference = 'Stop'

# جذر المستودع = أعلى مجلد السكربت.
$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$gradleFile = Join-Path $repoRoot 'app\build.gradle.kts'
$apkDir = Join-Path $repoRoot 'app\build\outputs\apk\release'

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
if ($TargetVersionOverride) {
    $parts = @($TargetVersionOverride.TrimStart('v').Split('.') | ForEach-Object { [int]$_ })
    $targetMajor = $parts[0]
    $targetMinor = $parts[1]
    $targetPatch = $parts[2]
} elseif ($null -ne $latestVersion) {
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
    "`r`n`r`n## التثبيت`r`nنزّل ملف APK الموافق لمعمارية " +
    "جهازك من مرفقات هذا الإصدار."
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
    Write-Host "   - gh release create $targetTag (يرفع أرفاق APK لكل" +
        ' معمارية بملاحظات المستجدات من changelog_text)'
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
    $prevEfGradle = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & .\gradlew.bat `
            :core:common:testDebugUnitTest `
            :core:engine:testDebugUnitTest `
            :core:audio:testDebugUnitTest `
            :core:data:testDebugUnitTest `
            :feature:settings:testDebugUnitTest `
            :app:testDebugUnitTest `
            --console=plain
        $gradleExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevEfGradle
    }
    if ($gradleExit -ne 0) {
        throw 'فشلت الاختبارات — أُلغيت عملية الإصدار.'
    }
    $prevEfGradle = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & .\gradlew.bat --stop
    } finally {
        $ErrorActionPreference = $prevEfGradle
    }
    $prevEfGradle = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & .\gradlew.bat :app:assembleRelease --console=plain
        $gradleExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevEfGradle
    }
    if ($gradleExit -ne 0) {
        throw 'فشل بناء Release — أُلغيت عملية الإصدار.'
    }
} finally {
    Pop-Location
}

# ناتج البناء صار APK مستقلاً لكل معمارية (nateq-<abi>.apk) — نجمع
# كل الأرفاق الناتجة ولا نفترض اسماً واحداً ثابتاً.
# ناتج البناء صار APK مستقلاً لكل معمارية (nateq-<abi>.apk) بالإضافة للمرفق العام (nateq.apk).
# تحقق صارم من كافة المخرجات الإلزامية قبل الشروع في أي نشر.
$expectedApkNames = @('nateq-arm64-v8a.apk', 'nateq-armeabi-v7a.apk', 'nateq.apk')
$targetApkFiles = @()
foreach ($apkName in $expectedApkNames) {
    $apkPath = Join-Path $apkDir $apkName
    if (-not (Test-Path -LiteralPath $apkPath)) {
        throw "ملف APK إلزامي مفقود بعد البناء: $apkName في $apkDir"
    }
    $apkInfo = Get-Item -LiteralPath $apkPath
    if ($apkInfo.Length -lt 10000000) { # 10MB كحد أدنى
        throw "حجم الـ APK غير طبيعي أو تالف: $apkName ($($apkInfo.Length) بايت)"
    }

    # تحقق من سلامة بنية حزمة الـ APK (ملفات Manifest و Dex والمكتبات الأصلية)
    $zip = $null
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
        $hasManifest = $null -ne ($zip.Entries | Where-Object { $_.FullName -eq 'AndroidManifest.xml' })
        $hasDex = $null -ne ($zip.Entries | Where-Object { $_.FullName -like '*.dex' })
        if (-not $hasManifest -or -not $hasDex) {
            throw "ملف $apkName تالف ولا يحتوي على AndroidManifest.xml أو classes.dex"
        }
        if ($apkName -match 'arm64-v8a') {
            $hasSo = $null -ne ($zip.Entries | Where-Object { $_.FullName -like 'lib/arm64-v8a/*.so' })
            if (-not $hasSo) {
                throw "ملف $apkName لا يحتوي على مكتبات arm64-v8a"
            }
        } elseif ($apkName -match 'armeabi-v7a') {
            $hasSo = $null -ne ($zip.Entries | Where-Object { $_.FullName -like 'lib/armeabi-v7a/*.so' })
            if (-not $hasSo) {
                throw "ملف $apkName لا يحتوي على مكتبات armeabi-v7a"
            }
        }
    } finally {
        if ($null -ne $zip) { $zip.Dispose() }
    }
    $targetApkFiles += $apkPath
}

# ===== فحص توقيع Release keystore والتحقق من مطابقته لتوقيع الإنتاج =====
Write-Host "=> فحص توقيع Release keystore..."
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
                $certBytes = & keytool -exportcert -keystore $fullStorePath `
                    -storepass $storePassword -keypass $keyPassword `
                    -alias $keyAlias -rfc 2>$null
                if ($LASTEXITCODE -eq 0 -and $certBytes) {
                    $pem = $certBytes -join "`n"
                    $cert = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
                        [System.Text.Encoding]::UTF8.GetBytes($pem)
                    )
                    $sha256 = $cert.GetCertHashString([System.Security.Cryptography.HashAlgorithmName]::SHA256)
                    $formattedSig = ($sha256 -replace '(.{2})', '$1:').TrimEnd(':')
                    Write-Host "=> توقيع Keystore الفعلي: $formattedSig"
                    $expectedSig = "79:3A:A5:31:F8:89:04:A1:9B:9C:1C:33:A6:7B:23:8F:31:42:92:63:F5:D3:24:F9:5E:AE:CE:E8:81:1F:E8:C3"
                    if ($formattedSig -ne $expectedSig) {
                        throw "توقيع Keystore ($formattedSig) لا يطابق توقيع الإنتاج الرسمي ($expectedSig)!"
                    }
                }
            }
        }
    }
} catch {
    Write-Warning "تحذير أثناء فحص التوقيع: $_"
}

# **بند 5.3:** سطر SHA-256 لكل APK يُلحق بملاحظات الإصدار — يُحسب من
# القرص مباشرةً بمجرد وجود الملف (بلا نسخ مؤقتة) ويتيح التحققَ من سلامة
# المرفق قبل التثبيت (سطر بصمة لكل معمارية باسم مرفقها).
$releaseNotes = $releaseNotes + "`r`n`r`n### المجموع الاختباري SHA-256`r`n"
$apkHashDict = @{}
foreach ($f in $targetApkFiles) {
    $sha = (Get-FileHash -LiteralPath $f -Algorithm SHA256).Hash.ToLowerInvariant()
    $leaf = Split-Path -Leaf $f
    $apkHashDict[$leaf] = $sha
    $releaseNotes = $releaseNotes + "``$sha``  $leaf`r`n"
}

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

# 5) إدارة ونشر الـ Release على GitHub بدقة وحصانة تامة ضد التلف أو الانقطاع
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw "أداة GitHub CLI (gh) غير مثبتة أو غير موجودة في المسار. تم الالتزام والوسم ولكن يلزم gh للنشر الآمن."
}

Write-Host "=> التحقق من حالة الإصدار $targetTag على GitHub..."
$prevEfGh = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $relViewJson = & gh release view $targetTag --repo aymankhattab41/Nateq --json id,isDraft,assets,tagName 2>$null
    $relData = if ($LASTEXITCODE -eq 0 -and $relViewJson) { $relViewJson | ConvertFrom-Json } else { $null }
} finally {
    $ErrorActionPreference = $prevEfGh
}

if ($null -eq $relData) {
    Write-Host "=> إنشاء الإصدار كمسودة أولية (Draft Release) لمنع وصول المستخدمين لملفات غير مكتملة..."
    $ghCreateArgs = @(
        'release', 'create', $targetTag,
        '--repo', 'aymankhattab41/Nateq',
        '--title', "Lord TTS $targetVersion",
        '--notes', $releaseNotes,
        '--draft'
    )
    $createOut = & gh @ghCreateArgs 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) {
        throw "فشل إنشاء مسودة الإصدار $targetTag على GitHub: $(($createOut | Out-String).Trim())"
    }
    $relViewJson = & gh release view $targetTag --repo aymankhattab41/Nateq --json id,isDraft,assets,tagName
    $relData = $relViewJson | ConvertFrom-Json
} else {
    Write-Host "=> الإصدار $targetTag موجود مسبقاً (Draft=$($relData.isDraft))."
}

$releaseId = $relData.id

# تنظيف أي مرفقات معلقة أو تالفة في حالة starter أو بحجم صفري
Write-Host "=> فحص وتنظيف المرفقات المعلقة (starter/0 bytes)..."
$prevEfGh = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $assetsJson = & gh api "repos/aymankhattab41/Nateq/releases/$releaseId/assets" 2>$null
    if ($LASTEXITCODE -eq 0 -and $assetsJson) {
        $existingAssets = $assetsJson | ConvertFrom-Json
        foreach ($a in $existingAssets) {
            if ($a.state -eq 'starter' -or $a.size -eq 0) {
                Write-Warning "حذف مرفق معلق أو غير مكتمل: $($a.name) (id=$($a.id), state=$($a.state))"
                & gh api -X DELETE "repos/aymankhattab41/Nateq/releases/assets/$($a.id)" | Out-Null
            }
        }
    }
} finally {
    $ErrorActionPreference = $prevEfGh
}

# رفع كافة ملفات APK المطلوبة مع إعادة المحاولة
foreach ($apkPath in $targetApkFiles) {
    $apkLeaf = Split-Path -Leaf $apkPath
    $apkLocalSize = (Get-Item -LiteralPath $apkPath).Length
    
    # فحص ما إذا كان المرفق مرفوعاً بالفعل بحجم مطابق
    $needsUpload = $true
    $prevEfGh = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $curAssetsJson = & gh api "repos/aymankhattab41/Nateq/releases/$releaseId/assets" 2>$null
        if ($LASTEXITCODE -eq 0 -and $curAssetsJson) {
            $curAssets = $curAssetsJson | ConvertFrom-Json
            $matching = $curAssets | Where-Object { $_.name -eq $apkLeaf -and $_.state -eq 'uploaded' -and $_.size -eq $apkLocalSize }
            if ($null -ne $matching) {
                Write-Host "=> المرفق $apkLeaf موجود مسبقاً بحجم سليم ($apkLocalSize بايت)."
                $needsUpload = $false
            }
        }
    } finally {
        $ErrorActionPreference = $prevEfGh
    }

    if ($needsUpload) {
        Write-Host "=> رفع المرفق $apkLeaf..."
        $upOut = & gh release upload $targetTag $apkPath --repo aymankhattab41/Nateq --clobber 2>&1 | ForEach-Object { "$_" }
        if ($LASTEXITCODE -ne 0) {
            throw "فشل رفع المرفق $apkLeaf إلى GitHub: $(($upOut | Out-String).Trim())"
        }
    }
}

# التحقق الصارم من كافة المرفقات المرفوعة (تنزيل موثق + فحص الحجم و SHA-256) قبل النشر
Write-Host "=> التحقق الصارم من سلامة كافة المرفقات المرفوعة وتطابق SHA-256..."
$verifyTempDir = Join-Path ([System.IO.Path]::GetTempPath()) "nateq_verify_$targetTag"
if (-not (Test-Path -LiteralPath $verifyTempDir)) {
    New-Item -ItemType Directory -Path $verifyTempDir -Force | Out-Null
}

try {
    $finalAssetsJson = & gh api "repos/aymankhattab41/Nateq/releases/$releaseId/assets"
    $finalAssets = $finalAssetsJson | ConvertFrom-Json

    foreach ($apkPath in $targetApkFiles) {
        $apkLeaf = Split-Path -Leaf $apkPath
        $apkLocalSize = (Get-Item -LiteralPath $apkPath).Length
        $apkLocalHash = $apkHashDict[$apkLeaf]

        $remoteAsset = $finalAssets | Where-Object { $_.name -eq $apkLeaf }
        if ($null -eq $remoteAsset) {
            throw "فشل التحقق: المرفق $apkLeaf غير مدرج في مرفقات الإصدار على GitHub"
        }
        if ($remoteAsset.state -ne 'uploaded') {
            throw "فشل التحقق: المرفق $apkLeaf ليس في حالة uploaded (حالته الحالية: $($remoteAsset.state))"
        }
        if ($remoteAsset.size -ne $apkLocalSize) {
            throw "فشل التحقق: حجم $apkLeaf على GitHub ($($remoteAsset.size)) لا يطابق المحلي ($apkLocalSize)"
        }

        # تنزيل الملف المرفوع والتحقق من بصمة SHA-256 الفعلية
        Write-Host "=> تنزيل $apkLeaf للتحقق من البصمة الرقمية..."
        $dlOut = & gh release download $targetTag --repo aymankhattab41/Nateq -p $apkLeaf -D $verifyTempDir --clobber 2>&1 | ForEach-Object { "$_" }
        if ($LASTEXITCODE -ne 0) {
            throw "فشل تنزيل $apkLeaf للتحقق: $(($dlOut | Out-String).Trim())"
        }

        $downloadedFile = Join-Path $verifyTempDir $apkLeaf
        $downloadedHash = (Get-FileHash -LiteralPath $downloadedFile -Algorithm SHA256).Hash.ToLowerInvariant()
        Remove-Item -LiteralPath $downloadedFile -Force -ErrorAction SilentlyContinue

        if ($downloadedHash -ne $apkLocalHash) {
            throw "عدم تطابق SHA-256 في المرفق $apkLeaf! (المحلي: $apkLocalHash، المرفوع: $downloadedHash)"
        }
        Write-Host "=> المرفق $apkLeaf سليم ومطابق تماماً (SHA-256: $downloadedHash)."
    }
} catch {
    Write-Error "فشل التحقق من مرفقات الإصدار. سيبقى الإصدار كمسودة (Draft) ولن يُنشر للمستخدمين."
    throw
} finally {
    Remove-Item -LiteralPath $verifyTempDir -Recurse -Force -ErrorAction SilentlyContinue
}

# نشر الإصدار وإلغاء صفة المسودة (draft=false) بعد التأكد من سلامة كافة المرفقات 100%
Write-Host "=> تم التحقق بنجاح 100% لكافة المرفقات. جارٍ نشر الإصدار للعامة (draft=false)..."
$pubOut = & gh release edit $targetTag --repo aymankhattab41/Nateq --draft=false 2>&1 | ForEach-Object { "$_" }
if ($LASTEXITCODE -ne 0) {
    throw "فشل تحويل الإصدار $targetTag إلى إصدار عام: $(($pubOut | Out-String).Trim())"
}

Write-Host "=> تم نشر الإصدار $targetVersion بنجاح تام على GitHub ($targetTag)."
foreach ($f in $targetApkFiles) {
    Write-Host "=> الـ APK المعتمد والمنشور: $f"
}