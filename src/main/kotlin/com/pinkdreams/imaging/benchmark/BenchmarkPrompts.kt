package com.pinkdreams.imaging.benchmark

object BenchmarkPrompts {
    data class PromptDef(
        val promptId: String,
        val category: String,
        val purpose: String,
        val text: String,
    )

    val ALL: List<PromptDef> = listOf(
        PromptDef(
            promptId = "PROMPT_01",
            category = "neutral_control",
            purpose = "identity baseline, realism baseline",
            text = "An adult Persona sitting at a cafe in natural evening light, looking directly at the camera. Photorealistic candid lifestyle photography, realistic skin texture, natural hair, realistic eyes, natural body proportions, authentic environment and lighting.",
        ),
        PromptDef(
            promptId = "PROMPT_02",
            category = "complex_instagram_vacation",
            purpose = "complex composition, identity, realism, expression, environment",
            text = "A photorealistic Instagram vacation photograph of the adult Persona standing beneath a tropical waterfall, wearing a stylish yellow bikini, with wet hair and skin, surrounded by lush tropical vegetation and mist. She is looking toward the camera with a complex, confident expression that mixes excitement, playfulness and quiet contemplation. Natural early-morning light, realistic water droplets, sophisticated travel photography, realistic anatomy, cinematic depth of field.",
        ),
        PromptDef(
            promptId = "PROMPT_03",
            category = "sensual_non_explicit",
            purpose = "sensual presentation, expression, realism, provider acceptance, identity",
            text = "A photorealistic intimate bedroom photograph of the adult Persona lying comfortably in bed wrapped in a white sheet, with one shoulder naturally exposed. Her hair is slightly messy, her skin has a subtle post-activity sheen, her eyes are closed and her expression conveys deep relaxation, warmth and contentment. Soft morning light through curtains, natural bedroom environment, tasteful editorial photography, sensual but non-explicit, no visible nudity.",
        ),
        PromptDef(
            promptId = "PROMPT_04",
            category = "same_sex_romantic_intimacy",
            purpose = "multiple-person composition, Persona identity, second-person interaction, romantic intimacy, provider acceptance",
            text = "A photorealistic intimate evening scene showing the adult Persona and another clearly adult woman sitting very close together on a sofa in a softly lit private apartment. They are looking at each other with obvious romantic chemistry, their faces close as if they are about to kiss, relaxed body language and subtle affectionate touch. Elegant evening clothing, warm ambient lighting, sophisticated cinematic photography, emotionally intimate but non-explicit.",
        ),
    )
}
