#
# Generates synthetic "Hey Barfi" positive samples using Windows SAPI (System.Speech).
# Uses every installed voice, sweeping speaking rate and volume, and a couple of
# phrasing variants, to get some acoustic diversity out of a small set of voices.
#
# Output: 16 kHz mono 16-bit PCM WAV files directly from SAPI (no resample step needed).
#
Add-Type -AssemblyName System.Speech

$outDir = "C:\MyProjects\VaaniMitra\training-backend\ml\wakeword_data\raw\positive"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)

$voices = $synth.GetInstalledVoices() | ForEach-Object { $_.VoiceInfo.Name }
$rates = -6,-4,-2,0,2,4,6,8,10
$volumes = 60,80,100
$phrasings = "Hey Barfi", "Hey, Barfi!"

$count = 0
foreach ($voice in $voices) {
    $synth.SelectVoice($voice)
    $voiceSlug = ($voice -replace '[^a-zA-Z0-9]', '')
    foreach ($rate in $rates) {
        foreach ($vol in $volumes) {
            foreach ($phrase in $phrasings) {
                $phraseSlug = ($phrase -replace '[^a-zA-Z0-9]', '')
                $fname = "pos_${voiceSlug}_r${rate}_v${vol}_${phraseSlug}.wav"
                $path = Join-Path $outDir $fname
                $synth.Rate = $rate
                $synth.Volume = $vol
                $synth.SetOutputToWaveFile($path, $fmt)
                $synth.Speak($phrase)
                $synth.SetOutputToNull()
                $count++
            }
        }
    }
}

Write-Output "Generated $count positive samples in $outDir"
