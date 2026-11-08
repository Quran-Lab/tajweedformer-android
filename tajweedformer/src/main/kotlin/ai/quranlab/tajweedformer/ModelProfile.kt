package ai.quranlab.tajweedformer

/**
 * Which files of a format 3 manifest a device needs (LAYOUT.md "Tags"). A file outside the profile in
 * use is never downloaded and never opened.
 *
 * - [cpu]: the exact engine, on every device and always first: every `required` file whose context is
 *   `*` or the chosen context, for platform `android`. About 56.5 MB of transport for c16_l64.
 * - [npu]: the optional `npu-*` files for this device's SoC and the chosen context (the model, its
 *   I/O contract, its mask table); downloaded only on an unmetered network after the CPU path passed
 *   its self-test. A chained build (the Exynos 2500) has its parts (`npu-part`, one file each, run in the
 *   order io.json's `parts` gives) in place of the one `npu-model`.
 *
 * NPU builds ship at c16_l64 only (LAYOUT.md "NPU builds"); another context has no `npu-*` files, so [npu]
 * is empty for it and the recognizer stays on the CPU engine (reason `no_npu_build`).
 */
object ModelProfile {
    /** The roles of a one-model NPU build, all three needed for the NPU backend. */
    @JvmField val NPU_ROLES = listOf("npu-model", "npu-io", "npu-masks")

    /** The role of each part of a chained NPU build (with `npu-io` and `npu-masks`, never with `npu-model`).
     *  SDKs before it look for `npu-model` only, so they see no build for such a SoC and stay on the CPU. */
    const val NPU_PART_ROLE = "npu-part"

    private val ALL_NPU_ROLES = NPU_ROLES + NPU_PART_ROLE

    /** The CPU profile of [context] for [platform]. */
    @JvmStatic
    @JvmOverloads
    fun cpu(m: ModelManifest, context: String, platform: String = TajweedFormer.PLATFORM): List<ModelManifest.FileEntry> {
        if (context !in m.contexts) throw RecognizerException.InvalidAppOption("context", "$context is not shipped (${m.contexts})")
        return m.files.filter {
            it.need == ModelManifest.Need.REQUIRED && it.forPlatform(platform) && (it.context == "*" || it.context == context)
        }
    }

    /** Every context's required files beyond [context]'s (another context later is about 0.9 MB). */
    @JvmStatic
    fun contextOnly(m: ModelManifest, context: String, platform: String = TajweedFormer.PLATFORM): List<ModelManifest.FileEntry> =
        cpu(m, context, platform).filter { it.context == context }

    /**
     * The NPU files for [soc] and [context], or an empty list when the model has no complete NPU build
     * for them: one `npu-io`, one `npu-masks`, and either one `npu-model` or one or more `npu-part`
     * (never both).
     */
    @JvmStatic
    @JvmOverloads
    fun npu(m: ModelManifest, soc: String?, context: String, platform: String = TajweedFormer.PLATFORM): List<ModelManifest.FileEntry> {
        if (soc == null) return emptyList()
        val files = m.files.filter {
            it.need == ModelManifest.Need.OPTIONAL && it.role in ALL_NPU_ROLES && it.forPlatform(platform) &&
                it.soc == soc && it.context == context
        }
        fun n(role: String) = files.count { it.role == role }
        val one = n("npu-model") == 1 && n(NPU_PART_ROLE) == 0
        val chain = n("npu-model") == 0 && n(NPU_PART_ROLE) >= 1
        return if ((one || chain) && n("npu-io") == 1 && n("npu-masks") == 1) files else emptyList()
    }

    /** The SoC ids the manifest has a complete NPU build of for [context]. */
    @JvmStatic
    fun npuSocs(m: ModelManifest, context: String): Set<String> =
        m.files.filter { it.role in ALL_NPU_ROLES && it.context == context && it.soc != "*" }.map { it.soc }.toSet()
            .filter { npu(m, it, context).isNotEmpty() }.toSet()

    /** Transport bytes of [files]. */
    @JvmStatic
    fun bytes(files: List<ModelManifest.FileEntry>): Long = files.sumOf { it.size }
}

/**
 * The device's SoC as the manifest's `soc` ids (LAYOUT.md: `exynos2600`, `exynos2500`, `tensor_g3`,
 * `tensor_g4`, `tensor_g5`, `tensor_g6`). Pure mapping from the strings Android reports, so it is tested on the
 * JVM; [ai.quranlab.tajweedformer.android.DeviceSoc] reads them on a device.
 *
 * Sources, in order: `Build.SOC_MANUFACTURER` + `Build.SOC_MODEL` (API 31+), then `ro.soc.model`,
 * then `Build.HARDWARE` / `ro.board.platform` / `ro.hardware`.
 *
 * Verified on real devices (11-07, logged by the NPU benchmark APK as
 * `soc=<SOC_MANUFACTURER> <SOC_MODEL> hardware=<HARDWARE>`):
 * - Pixel 8 (shiba): `Google` `Tensor G3`, hardware `shiba`
 * - Pixel 9 (tokay): `Google` `Tensor G4`, hardware `tokay`
 * - Pixel 10 (frankel): `Google` `Tensor G5`, hardware `frankel`
 * - Exynos 2600 reference board: `ro.soc.model` starts with `s5e`; the board is E9965 (`s5e9965`) in the compiler's `--soc_model=E9965`.
 *
 * - Exynos 2500 board (same farm, 11-08): E9955, `s5e9955` in the compiler's `--soc_model=E9955`; the farm
 *   script reads `ro.soc.model` the same way as on the 2600 board.
 *
 * Guessed (not seen on a device): the Exynos 2500's `Build.SOC_MODEL` on a retail Galaxy (Z Flip7; taken as
 * `s5e9955` or containing `9955` / `2500`), `Tensor G6` (no device exists in any lab), the Exynos 2600's
 * `Build.SOC_MODEL` on a retail Galaxy (taken as `s5e9965` or containing `9965` / `2600`), and the
 * board platform names `zuma` (G3), `zumapro` (G4), `laguna` (G5) used only below API 31, where no
 * Tensor G3 or later device exists (they ship with API 34 or later), so that path is a safety net.
 * Hardware codenames are used only as a fallback for Pixels whose SoC fields are empty.
 */
object SocIds {
    private val PIXEL_CODENAMES = mapOf(
        // Tensor G3: Pixel 8, 8 Pro, 8a
        "shiba" to "tensor_g3", "husky" to "tensor_g3", "akita" to "tensor_g3",
        // Tensor G4: Pixel 9, 9 Pro, 9 Pro XL, 9 Pro Fold, 9a
        "tokay" to "tensor_g4", "caiman" to "tensor_g4", "komodo" to "tensor_g4", "comet" to "tensor_g4", "tegu" to "tensor_g4",
        // Tensor G5: Pixel 10, 10 Pro, 10 Pro XL, 10 Pro Fold
        "frankel" to "tensor_g5", "blazer" to "tensor_g5", "mustang" to "tensor_g5", "rango" to "tensor_g5",
    )

    private val BOARDS = mapOf("zuma" to "tensor_g3", "zumapro" to "tensor_g4", "laguna" to "tensor_g5", "s5e9965" to "exynos2600",
        "s5e9955" to "exynos2500")

    /**
     * The SoC id for these Build / property values, or null when none is known. All arguments may be
     * null or empty.
     */
    @JvmStatic
    fun from(
        socManufacturer: String?,
        socModel: String?,
        roSocModel: String? = null,
        hardware: String? = null,
        boardPlatform: String? = null,
    ): String? {
        fun norm(s: String?) = s?.trim()?.lowercase().orEmpty()
        val man = norm(socManufacturer)
        for (model in listOf(norm(socModel), norm(roSocModel))) {
            if (model.isEmpty()) continue
            Regex("tensor\\s*g(\\d+)").find(model)?.let { return "tensor_g${it.groupValues[1]}" }
            if (model.contains("9965") || model.contains("exynos2600") || model.contains("exynos 2600")) return "exynos2600"
            if (model.contains("9955") || model.contains("exynos2500") || model.contains("exynos 2500")) return "exynos2500"
            if (man == "google" && model.matches(Regex("g(\\d+)"))) return "tensor_g${model.substring(1)}"
        }
        BOARDS[norm(boardPlatform)]?.let { return it }
        PIXEL_CODENAMES[norm(hardware)]?.let { return it }
        return BOARDS[norm(hardware)]
    }
}
