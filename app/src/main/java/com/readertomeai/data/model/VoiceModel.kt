package com.readertomeai.data.model

data class VoiceModel(
    val id: String,
    val name: String,
    val language: String,
    val quality: VoiceQuality,
    val sampleRate: Int = 22050,
    /** URL to the .onnx model file */
    val modelUrl: String,
    /** URL to the tokens.txt file (NOT the .onnx.json) */
    val tokensUrl: String,
    /** URL to espeak-ng-data.tar.bz2 archive (shared across voices) */
    val espeakDataUrl: String = ESPEAK_NG_DATA_URL,
    val sizeInMb: Int,
    val isDownloaded: Boolean = false,
    val localModelPath: String? = null,
    val localTokensPath: String? = null,
    val localDataDir: String? = null
)

enum class VoiceQuality {
    LOW, MEDIUM, HIGH
}

/** Shared espeak-ng-data archive used by all Piper VITS voices via sherpa-onnx */
private const val ESPEAK_NG_DATA_URL =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2"

/**
 * Pre-defined Piper voice models.
 *
 * Each entry points to the **sherpa-onnx compatible** model archive
 * hosted on HuggingFace under the rhasspy/piper-voices repo.
 * We download:
 *   1. <id>.onnx         — the VITS ONNX model
 *   2. tokens.txt        — integer→phoneme mapping sherpa-onnx expects
 *   3. espeak-ng-data/   — shared IPA front-end data (downloaded once)
 *
 * The tokens.txt files are extracted from the sherpa-onnx release
 * assets, NOT from the .onnx.json config that Piper standalone uses.
 */
object AvailableVoices {

    const val DEFAULT_VOICE_ID = "vits-piper-en_US-lessac-medium"
    const val COMPACT_FALLBACK_VOICE_ID = "vits-piper-en_US-ryan-high-int8"

    private const val BASE =
        "https://huggingface.co/rhasspy/piper-voices/resolve/main/en"

    /**
     * Sherpa-onnx publishes pre-converted model packs.  We use those URLs.
     * See https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html
     */
    private const val SHERPA_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

    val voices = listOf(
        VoiceModel(
            id = "vits-piper-en_US-ryan-high-int8",
            name = "Ryan High Compact (US)",
            language = "en-US",
            quality = VoiceQuality.HIGH,
            sampleRate = 22050,
            modelUrl = "$SHERPA_BASE/vits-piper-en_US-ryan-high-int8.tar.bz2",
            tokensUrl = "",
            sizeInMb = 33
        ),
        VoiceModel(
            id = "vits-piper-en_US-ryan-medium",
            name = "Ryan (US)",
            language = "en-US",
            quality = VoiceQuality.MEDIUM,
            sampleRate = 22050,
            modelUrl = "$SHERPA_BASE/vits-piper-en_US-ryan-medium.tar.bz2",
            tokensUrl = "",
            sizeInMb = 64
        ),
        VoiceModel(
            id = "vits-piper-en_US-amy-medium",
            name = "Amy (US)",
            language = "en-US",
            quality = VoiceQuality.MEDIUM,
            sampleRate = 22050,
            modelUrl = "$SHERPA_BASE/vits-piper-en_US-amy-medium.tar.bz2",
            tokensUrl = "", // tokens.txt is inside the tar archive
            sizeInMb = 64
        ),
        VoiceModel(
            id = "vits-piper-en_US-lessac-medium",
            name = "Lessac (US)",
            language = "en-US",
            quality = VoiceQuality.MEDIUM,
            sampleRate = 22050,
            modelUrl = "$SHERPA_BASE/vits-piper-en_US-lessac-medium.tar.bz2",
            tokensUrl = "",
            sizeInMb = 64
        ),
        VoiceModel(
            id = "vits-piper-en_US-libritts_r-medium",
            name = "LibriTTS (US)",
            language = "en-US",
            quality = VoiceQuality.MEDIUM,
            sampleRate = 22050,
            modelUrl = "$SHERPA_BASE/vits-piper-en_US-libritts_r-medium.tar.bz2",
            tokensUrl = "",
            sizeInMb = 78
        ),
        VoiceModel(
            id = "vits-piper-en_GB-alba-medium",
            name = "Alba (UK)",
            language = "en-GB",
            quality = VoiceQuality.MEDIUM,
            sampleRate = 22050,
            modelUrl = "$SHERPA_BASE/vits-piper-en_GB-alba-medium.tar.bz2",
            tokensUrl = "",
            sizeInMb = 64
        ),
        VoiceModel(
            id = "vits-piper-en_US-amy-low",
            name = "Amy Low (US)",
            language = "en-US",
            quality = VoiceQuality.LOW,
            sampleRate = 16000,
            modelUrl = "$SHERPA_BASE/vits-piper-en_US-amy-low.tar.bz2",
            tokensUrl = "",
            sizeInMb = 64
        )
    )
}
