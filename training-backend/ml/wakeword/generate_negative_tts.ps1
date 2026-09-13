#
# Synthesizes hard-negative text variants (from openwakeword.data.generate_adversarial_texts,
# phonetically similar / partial-phrase to "hey barfi") using every installed SAPI voice.
#
Add-Type -AssemblyName System.Speech

$jsonPath = "C:\MyProjects\VaaniMitra\training-backend\ml\wakeword_data\adversarial_texts.json"
$outDir = "C:\MyProjects\VaaniMitra\training-backend\ml\wakeword_data\raw\negative_tts"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$texts = Get-Content $jsonPath -Raw | ConvertFrom-Json

$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$synth.Volume = 90

$voices = $synth.GetInstalledVoices() | ForEach-Object { $_.VoiceInfo.Name }
$rates = 0,3

$count = 0
$idx = 0
foreach ($text in $texts) {
    $idx++
    foreach ($voice in $voices) {
        $synth.SelectVoice($voice)
        $voiceSlug = ($voice -replace '[^a-zA-Z0-9]', '')
        # Only use the extra rate variant on a subset (every 3rd text) to keep the count reasonable
        $rateList = if ($idx % 3 -eq 0) { $rates } else { ,0 }
        foreach ($rate in $rateList) {
            $synth.Rate = $rate
            $textSlug = ($text -replace '[^a-zA-Z0-9]', '').Substring(0, [Math]::Min(30, ($text -replace '[^a-zA-Z0-9]', '').Length))
            if ($textSlug -eq "") { $textSlug = "x$idx" }
            $fname = "neg_${idx}_${voiceSlug}_r${rate}_${textSlug}.wav"
            $path = Join-Path $outDir $fname
            try {
                $synth.SetOutputToWaveFile($path, $fmt)
                $synth.Speak($text)
                $synth.SetOutputToNull()
                $count++
            } catch {
                Write-Output "Skipped '$text' ($voice): $_"
            }
        }
    }
}

Write-Output "Generated $count negative TTS samples in $outDir"
