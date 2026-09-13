#
# Small extra batch of "Hey Barfi" positives for eval only, using rate/volume/phrasing
# combinations that do NOT appear in the training grid (generate_positive_tts.ps1 used
# rates -6,-4,-2,0,2,4,6,8,10 / volumes 60,80,100 / phrasings "Hey Barfi","Hey, Barfi!").
#
Add-Type -AssemblyName System.Speech

$outDir = "C:\MyProjects\VaaniMitra\training-backend\ml\wakeword_data\raw\positive_eval_unseen"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)

$voices = $synth.GetInstalledVoices() | ForEach-Object { $_.VoiceInfo.Name }
$rates = -9,-7,-5,-3,-1,1,3,5,7,9
$volumes = 50,70,90,100
$phrasings = "Hey Barfi.", "Hey Barfi please", "Okay, Hey Barfi"

$count = 0
foreach ($voice in $voices) {
    $synth.SelectVoice($voice)
    $voiceSlug = ($voice -replace '[^a-zA-Z0-9]', '')
    foreach ($rate in $rates) {
        $vol = $volumes[$count % $volumes.Length]
        $phrase = $phrasings[$count % $phrasings.Length]
        $phraseSlug = ($phrase -replace '[^a-zA-Z0-9]', '')
        $fname = "posu_${voiceSlug}_r${rate}_v${vol}_${phraseSlug}.wav"
        $path = Join-Path $outDir $fname
        $synth.Rate = $rate
        $synth.Volume = $vol
        $synth.SetOutputToWaveFile($path, $fmt)
        $synth.Speak($phrase)
        $synth.SetOutputToNull()
        $count++
    }
}

Write-Output "Generated $count unseen-grid positive eval samples in $outDir"
