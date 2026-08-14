param(
    [string]$Serial = "",
    [string]$ApkPath = "app/build/outputs/apk/debug/app-debug.apk",
    [string]$EnvPath = ".env.local",
    [string]$PrivateProfilePath = ".voiceflow-private.json",
    [switch]$SkipInstall,
    # Seed API keys only. Skips everything that encodes the owner's personal
    # setup — the "Wife" voice style, private vocabulary, hiding Work, forcing
    # the translation button — which is wrong to push onto someone else's phone.
    [switch]$KeysOnly,
    [switch]$EnableChinese,
    [ValidateSet("qwerty", "keypad")]
    [string]$ChineseLayout = "qwerty",
    # Display name for the built-in partner voice style.
    [string]$PartnerLabel = "Boobee",
    # Seeds the partner style even in -KeysOnly mode, for a second person's phone.
    [switch]$SeedPartnerStyle
)

$ErrorActionPreference = "Stop"

$PackageName = "com.voiceflowkeyboard.ime"
$PrefsFileName = "voiceflow_keyboard_settings.xml"

function Resolve-Adb {
    $localAdb = Join-Path (Get-Location) ".tooling/android-sdk/platform-tools/adb.exe"
    if (Test-Path $localAdb) {
        return (Resolve-Path $localAdb).Path
    }
    return "adb"
}

function Read-DotEnv([string]$Path) {
    $values = @{}
    if (!(Test-Path $Path)) {
        return $values
    }

    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*#' -or $line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
            continue
        }

        $name = $Matches[1]
        $value = $Matches[2].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$name] = $value
    }
    return $values
}

function First-EnvValue($Values, [string[]]$Names) {
    foreach ($name in $Names) {
        if ($Values.ContainsKey($name) -and ![string]::IsNullOrWhiteSpace($Values[$name])) {
            return $Values[$name].Trim()
        }
    }
    return ""
}

function New-PrefsDocument([string]$RawXml) {
    # On a first install there is no prefs file yet, and `run-as ... cat` reports
    # "No such file or directory" into the captured output. That text is not XML,
    # so the cast has to be guarded: an unguarded [xml] cast throws before any
    # fallback can run, which aborts the whole seeding step on every fresh device.
    $document = $null
    if (![string]::IsNullOrWhiteSpace($RawXml)) {
        try {
            $document = [xml]$RawXml
        } catch {
            $document = $null
        }
    }
    if ($null -eq $document -or $null -eq $document.map) {
        $document = [xml]"<?xml version='1.0' encoding='utf-8'?><map />"
    }
    return $document
}

function Set-StringPreference([xml]$Document, [string]$Name, [string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $false
    }

    $existing = $Document.SelectSingleNode("/map/string[@name='$Name']")
    if ($null -eq $existing) {
        $existing = $Document.CreateElement("string")
        $attribute = $Document.CreateAttribute("name")
        $attribute.Value = $Name
        [void]$existing.Attributes.Append($attribute)
        [void]$Document.DocumentElement.AppendChild($existing)
    }
    $existing.InnerText = $Value.Trim()
    return $true
}

function Set-BooleanPreference([xml]$Document, [string]$Name, [bool]$Value) {
    $existing = $Document.SelectSingleNode("/map/boolean[@name='$Name']")
    if ($null -eq $existing) {
        $existing = $Document.CreateElement("boolean")
        $nameAttribute = $Document.CreateAttribute("name")
        $nameAttribute.Value = $Name
        [void]$existing.Attributes.Append($nameAttribute)
        [void]$Document.DocumentElement.AppendChild($existing)
    }
    $valueAttribute = $existing.Attributes["value"]
    if ($null -eq $valueAttribute) {
        $valueAttribute = $Document.CreateAttribute("value")
        [void]$existing.Attributes.Append($valueAttribute)
    }
    $valueAttribute.Value = $Value.ToString().ToLowerInvariant()
}

function Remove-Preference([xml]$Document, [string]$Name) {
    $removed = 0
    foreach ($node in @($Document.SelectNodes("/map/*[@name='$Name']"))) {
        if ($null -ne $node.ParentNode) {
            [void]$node.ParentNode.RemoveChild($node)
            $removed++
        }
    }
    return $removed
}

function Add-PrivatePartnerVoiceStyle([xml]$Document, [string]$Label) {
    $existing = $Document.SelectSingleNode("/map/string[@name='prompts_json']")
    $profiles = @()
    if ($null -ne $existing -and ![string]::IsNullOrWhiteSpace($existing.InnerText)) {
        try {
            $decodedProfiles = $existing.InnerText | ConvertFrom-Json
            foreach ($profile in $decodedProfiles) {
                $profiles += $profile
            }
        } catch {
            $profiles = @()
        }
    }

    if (!($profiles | Where-Object { $_.id -eq "casual" })) {
        $profiles += [pscustomobject]@{ id = "casual"; name = "Friends" }
    }
    $partner = $profiles | Where-Object { $_.id -eq "partner" } | Select-Object -First 1
    if ($null -eq $partner) {
        $profiles += [pscustomobject]@{ id = "partner"; name = $Label }
    } else {
        $partner.name = $Label
    }
    [void](Set-StringPreference $Document "prompts_json" ($profiles | ConvertTo-Json -Compress))
}

function Merge-PrivateVoiceStyles([xml]$Document, [string]$Path) {
    if (!(Test-Path -LiteralPath $Path)) {
        return [pscustomobject]@{ Seeded = 0; Removed = 0 }
    }

    try {
        $privateProfile = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        throw "Could not parse private profile at $Path."
    }
    $profiles = @()
    $existing = $Document.SelectSingleNode("/map/string[@name='prompts_json']")
    if ($null -ne $existing -and ![string]::IsNullOrWhiteSpace($existing.InnerText)) {
        try {
            foreach ($profile in ($existing.InnerText | ConvertFrom-Json)) {
                $profiles += [pscustomobject]@{
                    id = [string]$profile.id
                    name = [string]$profile.name
                    icon = [string]$profile.icon
                }
            }
        } catch {
            $profiles = @()
        }
    }

    $removedCount = 0
    foreach ($removeValue in @($privateProfile.removeVoiceStyleIds)) {
        $removeId = ([string]$removeValue).Trim()
        if ($removeId -notmatch '^custom_[A-Za-z0-9_]+$') {
            continue
        }

        $profileWasPresent = $null -ne ($profiles | Where-Object {
            [string]::Equals([string]$_.id, $removeId, [System.StringComparison]::OrdinalIgnoreCase)
        } | Select-Object -First 1)
        $profiles = @($profiles | Where-Object {
            ![string]::Equals([string]$_.id, $removeId, [System.StringComparison]::OrdinalIgnoreCase)
        })

        $removedPreferenceCount = 0
        foreach ($preferenceName in @(
            "prompt_$removeId",
            "style_guidance_$removeId",
            "expression_override_$removeId",
            "expression_$removeId",
            "transform_model_override_openai_$removeId",
            "transform_model_override_anthropic_$removeId",
            "transform_model_override_xai_$removeId"
        )) {
            $removedPreferenceCount += Remove-Preference $Document $preferenceName
        }

        $activePreset = $Document.SelectSingleNode("/map/string[@name='active_preset']")
        if ($null -ne $activePreset -and [string]::Equals(
            $activePreset.InnerText,
            $removeId,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
            $activePreset.InnerText = "casual"
        }
        if ($profileWasPresent -or $removedPreferenceCount -gt 0) {
            $removedCount++
        }
    }

    $seededCount = 0
    foreach ($item in @($privateProfile.voiceStyles)) {
        $id = ([string]$item.id).Trim()
        $name = ([string]$item.name).Trim()
        $icon = ([string]$item.icon).Trim()
        if ($id -notmatch '^(casual|business|family|partner|custom_[A-Za-z0-9_]+)$' -or [string]::IsNullOrWhiteSpace($name)) {
            continue
        }

        $entry = [pscustomobject]@{ id = $id; name = $name; icon = $icon }
        $replaced = $false
        for ($index = 0; $index -lt $profiles.Count; $index++) {
            if ([string]::Equals([string]$profiles[$index].id, $id, [System.StringComparison]::OrdinalIgnoreCase)) {
                $profiles[$index] = $entry
                $replaced = $true
                break
            }
        }
        if (!$replaced) {
            $profiles += $entry
        }

        $prompt = ([string]$item.prompt).Trim()
        $styleGuidance = ([string]$item.styleGuidance).Trim()
        $expressionOverride = ([string]$item.expressionOverride).Trim()
        $transformProvider = ([string]$item.transformProvider).Trim().ToLowerInvariant()
        $transformModel = ([string]$item.transformModel).Trim()
        if (![string]::IsNullOrWhiteSpace($prompt)) {
            [void](Set-StringPreference $Document ("prompt_" + $id) $prompt)
        }
        if (![string]::IsNullOrWhiteSpace($styleGuidance)) {
            [void](Set-StringPreference $Document ("style_guidance_" + $id) $styleGuidance)
        }
        if (![string]::IsNullOrWhiteSpace($expressionOverride)) {
            [void](Set-StringPreference $Document ("expression_override_" + $id) $expressionOverride)
        }
        if (![string]::IsNullOrWhiteSpace($transformModel)) {
            if ($transformProvider -notin @("openai", "anthropic", "xai")) {
                $transformProvider = "openai"
            }
            [void](Set-StringPreference $Document ("transform_model_override_" + $transformProvider + "_" + $id) $transformModel)
        }
        $seededCount++
    }

    if ($seededCount -gt 0 -or $removedCount -gt 0) {
        [void](Set-StringPreference $Document "prompts_json" ($profiles | ConvertTo-Json -Compress))
    }
    return [pscustomobject]@{ Seeded = $seededCount; Removed = $removedCount }
}

function ConvertTo-JsonArray($Items) {
    $values = @($Items)
    if ($values.Count -eq 0) {
        return "[]"
    }
    return (ConvertTo-Json -InputObject $values -Compress -Depth 6)
}

# The private build has no use for the Work style. The app no longer force-re-adds it,
# so dropping it here keeps it hidden; "+ Work" stays available in Settings to restore it.
function Hide-WorkVoiceStyle([xml]$Document) {
    $workIds = @("business", "professional")
    $removed = $false

    $existing = $Document.SelectSingleNode("/map/string[@name='prompts_json']")
    if ($null -ne $existing -and ![string]::IsNullOrWhiteSpace($existing.InnerText)) {
        $profiles = @()
        try {
            foreach ($profile in ($existing.InnerText | ConvertFrom-Json)) {
                $profiles += $profile
            }
        } catch {
            $profiles = @()
        }
        $kept = @($profiles | Where-Object { $workIds -notcontains ([string]$_.id).Trim().ToLowerInvariant() })
        if ($kept.Count -ne $profiles.Count) {
            [void](Set-StringPreference $Document "prompts_json" (ConvertTo-JsonArray $kept))
            $removed = $true
        }
    }

    $activePreset = $Document.SelectSingleNode("/map/string[@name='active_preset']")
    if ($null -ne $activePreset -and $workIds -contains $activePreset.InnerText.Trim().ToLowerInvariant()) {
        $activePreset.InnerText = "casual"
        $removed = $true
    }
    return $removed
}

function Merge-PrivateVocabulary([xml]$Document, [string]$Path) {
    if (!(Test-Path -LiteralPath $Path)) {
        return 0
    }

    try {
        $privateProfile = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        throw "Could not parse private profile at $Path."
    }
    if ($null -eq $privateProfile.replacements) {
        return 0
    }

    $merged = @()
    $existing = $Document.SelectSingleNode("/map/string[@name='replacements_json']")
    if ($null -ne $existing -and ![string]::IsNullOrWhiteSpace($existing.InnerText)) {
        try {
            foreach ($item in ($existing.InnerText | ConvertFrom-Json)) {
                if (![string]::IsNullOrWhiteSpace($item.from) -and ![string]::IsNullOrWhiteSpace($item.to)) {
                    $merged += [pscustomobject]@{
                        from = [string]$item.from
                        to = [string]$item.to
                        context = [string]$item.context
                    }
                }
            }
        } catch {
            $merged = @()
        }
    }

    $seededCount = 0
    foreach ($item in $privateProfile.replacements) {
        $from = ([string]$item.from).Trim()
        $to = ([string]$item.to).Trim()
        $context = ([string]$item.context).Trim()
        if ([string]::IsNullOrWhiteSpace($from) -or [string]::IsNullOrWhiteSpace($to)) {
            continue
        }

        $entry = [pscustomobject]@{ from = $from; to = $to; context = $context }
        $replaced = $false
        for ($index = 0; $index -lt $merged.Count; $index++) {
            if ([string]::Equals([string]$merged[$index].to, $to, [System.StringComparison]::OrdinalIgnoreCase)) {
                $merged[$index] = $entry
                $replaced = $true
                break
            }
        }
        if (!$replaced) {
            $merged += $entry
        }
        $seededCount++
    }

    if ($seededCount -gt 0) {
        [void](Set-StringPreference $Document "replacements_json" ($merged | ConvertTo-Json -Compress))
    }
    return $seededCount
}

function Save-XmlUtf8([xml]$Document, [string]$Path) {
    $settings = New-Object System.Xml.XmlWriterSettings
    $settings.Encoding = New-Object System.Text.UTF8Encoding($false)
    $settings.Indent = $true
    $writer = [System.Xml.XmlWriter]::Create($Path, $settings)
    try {
        $Document.Save($writer)
    } finally {
        $writer.Close()
    }
}

function Assert-LastExit([string]$Action) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Action failed with exit code $LASTEXITCODE."
    }
}

$adb = Resolve-Adb
$serialArgs = @()
if (![string]::IsNullOrWhiteSpace($Serial)) {
    $serialArgs = @("-s", $Serial)
}

$resolvedApk = Resolve-Path -LiteralPath $ApkPath

if (!$SkipInstall) {
    & $adb @serialArgs install -r $resolvedApk.Path | Out-Host
    Assert-LastExit "APK install"
}

$envValues = Read-DotEnv $EnvPath
$keys = @(
    @{
        Pref = "openai_api_key"
        Label = "OpenAI"
        Names = @("OpenAIAPIKey", "OPENAI_API_KEY", "OPENAI_APIKEY")
    },
    @{
        Pref = "anthropic_api_key"
        Label = "Anthropic"
        Names = @("AnthropicAPIKey", "ANTHROPIC_API_KEY", "ANTHROPIC_APIKEY")
    },
    @{
        Pref = "xai_api_key"
        Label = "xAI"
        Names = @("XAIAPIKey", "XAI_API_KEY", "XAI_APIKEY")
    },
    @{
        Pref = "deepgram_api_key"
        Label = "Deepgram"
        Names = @("DeepgramAPIKey", "DEEPGRAM_API_KEY", "DEEPGRAM_APIKEY")
    }
)

$existingXml = ""
try {
    $existingXml = (& $adb @serialArgs exec-out run-as $PackageName cat "shared_prefs/$PrefsFileName" 2>$null) -join "`n"
    $existingXml = $existingXml -replace "`0", ""
} catch {
    $existingXml = ""
}

$document = New-PrefsDocument $existingXml
$seeded = New-Object System.Collections.Generic.List[string]
foreach ($key in $keys) {
    $value = First-EnvValue $envValues $key.Names
    if (Set-StringPreference $document $key.Pref $value) {
        [void]$seeded.Add($key.Label)
    }
}

$privateVoiceStyleCount = 0
$privateVoiceStyleRemovedCount = 0
$privateVocabularyCount = 0
$workVoiceStyleHidden = $false

if ($KeysOnly -and $SeedPartnerStyle) {
    # Second person's phone: give them the partner style and nothing else personal.
    Add-PrivatePartnerVoiceStyle $document $PartnerLabel
}

if (!$KeysOnly) {
    [void](Set-StringPreference $document "translation_target_language" "Chinese (Simplified)")
    Set-BooleanPreference $document "translation_enabled" $true
    $privateVoiceStyleResult = Merge-PrivateVoiceStyles $document $PrivateProfilePath
    $privateVoiceStyleCount = $privateVoiceStyleResult.Seeded
    $privateVoiceStyleRemovedCount = $privateVoiceStyleResult.Removed
    $privateVocabularyCount = Merge-PrivateVocabulary $document $PrivateProfilePath
    # After the merge, so -PartnerLabel stays authoritative: a stale name inside
    # .voiceflow-private.json would otherwise overwrite it and the run would
    # report the new label while writing the old one.
    Add-PrivatePartnerVoiceStyle $document $PartnerLabel
    $workVoiceStyleHidden = Hide-WorkVoiceStyle $document
}

if ($EnableChinese) {
    Set-BooleanPreference $document "chinese_input_enabled" $true
    [void](Set-StringPreference $document "chinese_layout" $ChineseLayout)
}

$tempXml = Join-Path ([System.IO.Path]::GetTempPath()) ("voiceflow-keyboard-prefs-" + [System.Guid]::NewGuid().ToString("N") + ".xml")
$remoteXml = "/data/local/tmp/voiceflow-keyboard-prefs.xml"
try {
    Save-XmlUtf8 $document $tempXml
    & $adb @serialArgs shell "am force-stop $PackageName" | Out-Null
    Assert-LastExit "Force-stop app"
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $adb @serialArgs push $tempXml $remoteXml 1>$null 2>$null
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    Assert-LastExit "Push temporary preferences"
    & $adb @serialArgs shell "chmod 644 $remoteXml" | Out-Null
    Assert-LastExit "Prepare temporary preferences"
    $copyCommand = "run-as $PackageName sh -c 'mkdir -p shared_prefs && cp $remoteXml shared_prefs/$PrefsFileName && chmod 600 shared_prefs/$PrefsFileName'"
    & $adb @serialArgs shell $copyCommand | Out-Null
    Assert-LastExit "Write app preferences"
    & $adb @serialArgs shell "rm $remoteXml" | Out-Null
    Assert-LastExit "Remove temporary preferences"
} finally {
    Remove-Item -LiteralPath $tempXml -ErrorAction SilentlyContinue
}

if ($seeded.Count -gt 0) {
    Write-Host ("Seeded API keys on device: " + ($seeded -join ", "))
} else {
    Write-Host "No API keys found in $EnvPath."
}
if ($KeysOnly) {
    Write-Host "Keys-only: skipped private vocabulary and translation defaults"
    if ($SeedPartnerStyle) {
        Write-Host "Seeded partner voice style: $PartnerLabel"
    }
} else {
    Write-Host "Enabled private translation button: Chinese (Simplified)"
    Write-Host "Enabled private voice style: $PartnerLabel"
    if ($workVoiceStyleHidden) {
        Write-Host "Hid voice style: Work (re-add it any time from Settings)"
    } else {
        Write-Host "Voice style Work already hidden"
    }
}
if ($EnableChinese) {
    Write-Host "Enabled Chinese input (layout: $ChineseLayout)"
}
if ($privateVoiceStyleCount -gt 0) {
    Write-Host "Seeded private voice style configurations: $privateVoiceStyleCount"
}
if ($privateVoiceStyleRemovedCount -gt 0) {
    Write-Host "Removed private voice styles: $privateVoiceStyleRemovedCount"
}
if ($privateVocabularyCount -gt 0) {
    Write-Host "Seeded private vocabulary entries: $privateVocabularyCount"
}
