package com.pinkdreams.imaging.benchmark

/**
 * Prompt IDs stay PROMPT_01–04 for every model.
 * Text is identical across models for a given Persona + prompt id.
 * Text is allowed to differ between Personas.
 */
object BenchmarkPersonaPrompts {
    fun resolve(personaSlug: String, promptId: String, fallback: String): String {
        val key = when (personaSlug.lowercase()) {
            "anaya", "aanya", "aanya_deshpande" -> "anaya"
            "zoya", "zoya_fatima" -> "zoya"
            "pihu", "pihu_mishra" -> "pihu"
            else -> return fallback
        }
        return VARIANTS[key]?.get(promptId) ?: fallback
    }

    private val VARIANTS: Map<String, Map<String, String>> = mapOf(
        "anaya" to mapOf(
            "PROMPT_01" to
                "An adult Persona sitting at a quiet neighborhood cafe in natural evening light, looking directly at the camera. She wears a modest mustard kurta or simple everyday layers, a cup of chai on the table, relaxed but a little reserved. Photorealistic candid lifestyle photography, realistic skin texture, natural hair, realistic eyes, natural body proportions, authentic environment and lighting.",
            "PROMPT_02" to
                "A photorealistic travel photograph of the adult Persona standing on wet stone near a tropical waterfall, wearing a modest stylish yellow two-piece swimsuit with fuller coverage, damp hair tied loosely, surrounded by lush vegetation and mist. She looks toward the camera with a warm, slightly shy smile — curious and happy, not performative. Natural early-morning light, realistic water droplets, sophisticated travel photography, realistic anatomy, cinematic depth of field.",
            "PROMPT_03" to
                "A photorealistic intimate bedroom photograph of the adult Persona lying comfortably in bed wrapped in a white sheet held high across her chest, one bare shoulder only, no visible nudity. Her hair is slightly messy, her expression is quiet and content, eyes softly closed as if resting after a long day. Soft morning light through curtains, natural bedroom environment, tasteful editorial photography, tender and non-explicit.",
            "PROMPT_04" to
                "A photorealistic intimate evening scene showing the adult Persona and another clearly adult woman sitting close together on a sofa in a softly lit private apartment. They lean in with shy romantic chemistry, faces near but not kissing, a light hand on a sleeve. Elegant modest evening clothing, warm ambient lighting, sophisticated cinematic photography, emotionally intimate but non-explicit. All people depicted must be clearly adults.",
        ),
        "zoya" to mapOf(
            "PROMPT_01" to
                "An adult Persona sitting at a stylish cafe in natural evening light, looking directly at the camera. Compact athletic build, platinum pixie, still in easy dance-casual clothes after class, a glass of water beside her. Photorealistic candid lifestyle photography, realistic skin texture, natural hair, realistic eyes, natural body proportions, authentic environment and lighting.",
            "PROMPT_02" to
                "A photorealistic Instagram vacation photograph of the adult Persona standing beneath a tropical waterfall, wearing a stylish yellow bikini that shows her athletic dancer’s body, wet short hair and skin, surrounded by lush tropical vegetation and mist. She looks toward the camera with a complex, confident expression that mixes excitement, playfulness and quiet contemplation. Natural early-morning light, realistic water droplets, sophisticated travel photography, realistic anatomy, cinematic depth of field.",
            "PROMPT_03" to
                "A photorealistic intimate bedroom photograph of the adult Persona lying on her side in bed, a white sheet draped around her hips and chest with one muscular shoulder and collarbone exposed, no visible nudity. Her hair is messy, her skin has a faint post-training sheen, eyes half-closed, expression warm, spent, and content. Soft morning light through curtains, natural bedroom environment, tasteful editorial photography, sensual but non-explicit.",
            "PROMPT_04" to
                "A photorealistic intimate evening scene showing the adult Persona and another clearly adult woman sitting very close together on a sofa in a softly lit private apartment. They look at each other with obvious romantic chemistry, faces close as if they are about to kiss, relaxed athletic body language and a hand on a knee. Elegant evening clothing, warm ambient lighting, sophisticated cinematic photography, emotionally intimate but non-explicit. All people depicted must be clearly adults.",
        ),
        "pihu" to mapOf(
            "PROMPT_01" to
                "An adult Persona sitting at a stylish cafe in natural evening light, looking directly at the camera. Caramel-balayage hair down, gold chain catching the light, after a shoot, a little more aware of the lens than a civilian. Photorealistic candid lifestyle photography, realistic skin texture, natural hair, realistic eyes, natural body proportions, authentic environment and lighting.",
            "PROMPT_02" to
                "A photorealistic Instagram vacation photograph of the adult Persona standing beneath a tropical waterfall, wearing a stylish revealing yellow bikini, wet hair plastered to her neck and shoulders, water running over warm skin, surrounded by lush tropical vegetation and mist. She looks toward the camera with a charged, confident expression that mixes excitement, playfulness and quiet heat. Natural early-morning light, realistic water droplets, sophisticated travel photography, realistic anatomy, cinematic depth of field.",
            "PROMPT_03" to
                "A photorealistic intimate bedroom photograph of the adult Persona lying back in rumpled white sheets that have slipped low across her chest, one shoulder and the upper curve of her chest visible, no nipples and no visible nudity. Her hair is messy across the pillow, skin flushed with a post-activity sheen, lips parted, eyes closed, expression deep, warm, and unmistakably after intimacy. Soft morning light through curtains, natural bedroom environment, tasteful editorial photography, sensual and heated but non-explicit.",
            "PROMPT_04" to
                "A photorealistic intimate evening scene showing the adult Persona and another clearly adult woman sitting very close together on a sofa in a softly lit private apartment. They are looking at each other with obvious romantic chemistry, faces close as if they are about to kiss, relaxed body language and subtle affectionate touch at the jaw. Elegant evening clothing, warm ambient lighting, sophisticated cinematic photography, emotionally intimate but non-explicit. All people depicted must be clearly adults.",
        ),
    )
}
