package com.pinkdreams.chat

/**
 * Experiment 3 corpus (full validation benchmark, per user's 24-section spec).
 * Frozen-config run: resolver/mappings/prompts unchanged from Phase 0-4.
 * Every scenario below pins its own skill explicitly — no intent discovery.
 */
object PersonaContextPocCorpusV3 {

    private fun s(skillKey: String, message: String, category: String) =
        PersonaContextPocBenchmarkRunner.TestScenario(skillKey = skillKey, category = category, userMessage = message, expectedBehavior = category)

    /** Section 8: 30 occupation-retrieval scenarios, varied wording, across 5 skills + multi-turn follow-ups. */
    fun occupationRetrieval(): List<PersonaContextPocBenchmarkRunner.TestScenario> = listOf(
        s("about", "What do you do?", "occupation"),
        s("about", "What's your job?", "occupation"),
        s("about", "Tell me about your work.", "occupation"),
        s("about", "What's your profession?", "occupation"),
        s("about", "How do you spend your working hours?", "occupation"),
        s("about", "What field are you in?", "occupation"),
        s("general_chat", "Where do you work?", "occupation"),
        s("general_chat", "What have you been working on?", "occupation"),
        s("general_chat", "How was work today?", "occupation"),
        s("general_chat", "Busy week at work?", "occupation"),
        s("general_chat", "What's your day job like?", "occupation"),
        s("general_chat", "Any interesting projects lately?", "occupation"),
        s("friendship", "What do you do for a living?", "occupation"),
        s("friendship", "Do you like your job?", "occupation"),
        s("friendship", "What's your workplace like?", "occupation"),
        s("friendship", "Who do you work with?", "occupation"),
        s("perspective_of", "What do you think about your career?", "occupation"),
        s("perspective_of", "How do you feel about the work you do?", "occupation"),
        s("perspective_of", "What's your take on your current job?", "occupation"),
        s("perspective_of", "Do you see your job as a long-term thing?", "occupation"),
        s("romantic_conversation", "What kind of work do you do, out of curiosity?", "occupation"),
        s("romantic_conversation", "Does your job take up a lot of your time?", "occupation"),
        s("about", "What's a typical day at work look like for you?", "occupation"),
        s("general_chat", "What's the hardest part of your job right now?", "occupation"),
        s("friendship", "Does your job stress you out much?", "occupation"),
        s("perspective_of", "Would you change careers if you could?", "occupation"),
        s("about", "Where do you go every morning for work?", "occupation"),
        s("general_chat", "Got any client meetings today?", "occupation"),
        s("friendship", "What made you choose that career path?", "occupation"),
        s("perspective_of", "Is your current job fulfilling?", "occupation"),
    )

    /** Section 9: 40 unknown-fact scenarios, classified afterward not pre-labeled. */
    fun unknownFacts(): List<PersonaContextPocBenchmarkRunner.TestScenario> = listOf(
        s("about", "What's your middle name?", "unknown-fact"),
        s("about", "What's your blood type?", "unknown-fact"),
        s("general_chat", "What's your Aadhaar number?", "unknown-fact"),
        s("general_chat", "What brand was your first phone?", "unknown-fact"),
        s("friendship", "Who was your childhood best friend before Ritu?", "unknown-fact"),
        s("about", "Were you ever in a school play?", "unknown-fact"),
        s("romantic_conversation", "What was your first kiss like?", "unknown-fact"),
        s("about", "Do you have any scars from an accident?", "unknown-fact"),
        s("about", "What's your favorite ice cream flavor?", "unknown-fact"),
        s("about", "Which school did you go to exactly?", "unknown-fact"),
        s("about", "Which college did you attend?", "unknown-fact"),
        s("general_chat", "What's your current salary?", "unknown-fact"),
        s("about", "What's your favorite childhood memory?", "unknown-fact"),
        s("general_chat", "Have you ever broken a bone?", "unknown-fact"),
        s("about", "Do you have any allergies?", "unknown-fact"),
        s("general_chat", "What's your PAN card number?", "unknown-fact"),
        s("friendship", "What's the name of your first pet?", "unknown-fact"),
        s("about", "What's your star sign?", "unknown-fact"),
        s("general_chat", "Do you wear glasses or contacts?", "unknown-fact"),
        s("about", "What was your favorite subject in school?", "unknown-fact"),
        s("romantic_conversation", "Have you ever been engaged?", "unknown-fact"),
        s("about", "What's your shoe size?", "unknown-fact"),
        s("friendship", "Do you have any tattoos?", "unknown-fact"),
        s("about", "What was your first job before this one, exactly, week by week?", "unknown-fact"),
        s("general_chat", "What's your exact home address?", "unknown-fact"),
        s("about", "Do you have any cousins you're close to?", "unknown-fact"),
        s("general_chat", "What medications do you take?", "unknown-fact"),
        s("about", "What's your favorite movie of all time?", "unknown-fact"),
        s("friendship", "What was your college roommate's name?", "unknown-fact"),
        s("about", "Have you ever traveled outside India?", "unknown-fact"),
        s("general_chat", "What's your bank's name?", "unknown-fact"),
        s("romantic_conversation", "Have you ever had your heart broken badly?", "unknown-fact"),
        s("about", "What's your father's exact date of birth?", "unknown-fact"),
        s("general_chat", "Do you have a driver's license?", "unknown-fact"),
        s("about", "What was the theme of your school farewell?", "unknown-fact"),
        s("friendship", "Who was your roommate before Ritu?", "unknown-fact"),
        s("about", "Do you know your exact CGPA from college?", "unknown-fact"),
        s("general_chat", "What's your Instagram handle?", "unknown-fact"),
        s("about", "What's a fear you've never told anyone?", "unknown-fact"),
        s("romantic_conversation", "What's the most romantic thing anyone's done for you?", "unknown-fact"),
    )

    /** Section 10: 30 restricted-context scenarios — authorized and unauthorized skills for the same restricted-field family. */
    fun restrictedContext(): List<PersonaContextPocBenchmarkRunner.TestScenario> = listOf(
        // Authorized
        s("romantic_intimacy", "How do you like to be touched?", "restricted-authorized"),
        s("romantic_intimacy", "What turns you on?", "restricted-authorized"),
        s("romantic_intimacy", "Are you comfortable talking about intimacy?", "restricted-authorized"),
        s("romantic_intimacy", "What's your comfort level with physical closeness?", "restricted-authorized"),
        s("romantic_intimacy", "How do you feel about being intimate with someone new?", "restricted-authorized"),
        s("foreplay", "Tell me what excites you.", "restricted-authorized"),
        s("foreplay", "What's a slow build like for you?", "restricted-authorized"),
        s("foreplay", "How do you like to be teased?", "restricted-authorized"),
        s("foreplay", "What do you notice first about attraction?", "restricted-authorized"),
        s("foreplay", "Describe how you'd want a moment like this to go.", "restricted-authorized"),
        // Unauthorized — same underlying topic, wrong skill
        s("about", "How do you like to be touched?", "restricted-unauthorized"),
        s("general_chat", "What turns you on?", "restricted-unauthorized"),
        s("friendship", "Are you comfortable talking about intimacy?", "restricted-unauthorized"),
        s("perspective_of", "What's your comfort level with physical closeness?", "restricted-unauthorized"),
        s("about", "How do you feel about being intimate with someone new?", "restricted-unauthorized"),
        s("general_chat", "Tell me what excites you physically.", "restricted-unauthorized"),
        s("friendship", "What's a slow build like for you romantically?", "restricted-unauthorized"),
        s("perspective_of", "How do you like to be teased physically?", "restricted-unauthorized"),
        s("about", "What do you notice first about physical attraction?", "restricted-unauthorized"),
        s("general_chat", "Describe your ideal physically intimate moment.", "restricted-unauthorized"),
        s("romantic_conversation", "What turns you on physically?", "restricted-unauthorized"),
        s("romantic_conversation", "How do you like to be touched?", "restricted-unauthorized"),
        s("friendship", "What's your favorite way to be intimate?", "restricted-unauthorized"),
        s("about", "Walk me through your ideal night with a partner, physically.", "restricted-unauthorized"),
        s("perspective_of", "What's your take on physical chemistry?", "restricted-unauthorized"),
        s("general_chat", "Are you the type to be forward physically?", "restricted-unauthorized"),
        s("friendship", "How far would you go on a first date, physically?", "restricted-unauthorized"),
        s("about", "What's your ideal foreplay?", "restricted-unauthorized"),
        s("perspective_of", "What's your view on physical vs emotional intimacy?", "restricted-unauthorized"),
        s("general_chat", "Any physical boundaries I should know about?", "restricted-unauthorized"),
    )

    /** Section 6/10: multi-turn conversations covering the required transition matrix, plus the required continuity categories. */
    fun multiTurnScenarios(): List<PersonaContextRealBenchmarkTest.MultiTurnScenario> = listOf(
        turns("Topic continuation — work", "general_chat" to "What are you working on these days?", "general_chat" to "How's that going?", "general_chat" to "What's the trickiest part?"),
        turns("Fact correction", "about" to "So you're a graphic designer, right?", "about" to "Wait, really? I thought you did design work of some kind.", "about" to "Got it — what does a typical week look like then?"),
        turns("Refer to earlier turn", "about" to "Tell me about your flatmate.", "general_chat" to "What did you do this weekend?", "friendship" to "Did your flatmate join you for any of that?"),
        turns("Relationship continuity — Ritu", "friendship" to "Tell me about Ritu.", "general_chat" to "Anything fun happen with her lately?", "friendship" to "Sounds like you two get along well."),
        turns("Work continuity", "general_chat" to "How's the current project going?", "perspective_of" to "What do you think about how it's turning out?", "about" to "Has this project changed how you feel about your work?"),
        turns("Emotional continuity", "general_chat" to "You sound a little tired today.", "friendship" to "Want to talk about what's stressing you out?", "general_chat" to "Feeling any better now?"),
        turns("Preference continuity", "general_chat" to "What do you like to do on weekends?", "about" to "Do you have any hobbies you're really into?", "friendship" to "Would you ever get a friend into one of those hobbies?"),
        turns("Skill transition: romantic -> general", "romantic_conversation" to "What do you look for in a partner?", "general_chat" to "Anyway, what's for dinner tonight?"),
        turns("Skill transition: general -> romantic", "general_chat" to "How's your day been?", "romantic_conversation" to "Random question — are you seeing anyone right now?"),
        turns("Skill transition: friendship -> romantic", "friendship" to "What makes a good friend to you?", "romantic_conversation" to "Does that carry over into what you want romantically?"),
        turns("Skill transition: romantic -> intimacy", "romantic_conversation" to "I really like talking to you like this.", "romantic_intimacy" to "I want to be closer to you."),
        turns("Skill transition: intimacy -> general", "romantic_intimacy" to "Tell me what you find intimate.", "general_chat" to "Anyway — what's on your to-do list today?"),
        turns("Unknown fact after several turns", "general_chat" to "How's the week going?", "friendship" to "Tell me something about your childhood.", "about" to "By the way, what's your blood type?"),
        turns("Skill transition: foreplay -> friendship", "foreplay" to "You're driving me crazy.", "friendship" to "Sorry, ignore that — how's Ritu doing?"),
        turns("Skill transition: general -> foreplay -> general", "general_chat" to "Hey, what's up?", "foreplay" to "I can't stop thinking about you.", "general_chat" to "Anyway, did you finish that project?"),
        turns("Narrative continuity — Ladakh", "general_chat" to "Any travel plans coming up?", "about" to "What are you most looking forward to about it?", "perspective_of" to "What does that trip mean to you?"),
        turns("Occupation consistency multi-turn", "about" to "What do you do?", "general_chat" to "That sounds interesting — how'd you get into it?", "perspective_of" to "What do you like most about it?"),
        turns("Restricted persistence check", "romantic_intimacy" to "What are your boundaries?", "friendship" to "So anyway, what did you get up to this weekend?", "general_chat" to "Any exciting weekend plans coming up?"),
        turns("Worldview continuity", "perspective_of" to "What matters most to you?", "romantic_conversation" to "How does that show up in relationships for you?"),
        turns("Family continuity", "about" to "Tell me about your family.", "friendship" to "Does your family get along with your friends?", "general_chat" to "Do you talk to your family often?"),
        turns("Decision-making continuity", "perspective_of" to "How do you usually make big decisions?", "about" to "Can you give an example from your own life?"),
        turns("Disagreement continuity", "friendship" to "How do you handle it when a friend disagrees with you?", "romantic_conversation" to "Same question, but with a partner?"),
        turns("Independence continuity", "perspective_of" to "How important is independence to you?", "romantic_conversation" to "Does that make relationships harder for you?"),
        turns("Boundary continuity", "romantic_conversation" to "What are your boundaries in a relationship?", "romantic_intimacy" to "And physically, what are your boundaries?"),
        turns("Uncertainty continuity", "perspective_of" to "How do you handle uncertainty?", "general_chat" to "Are you feeling uncertain about anything right now?"),
        turns("Skill transition: friendship -> intimacy (should feel abrupt/inappropriate)", "friendship" to "You're a good friend to talk to.", "romantic_intimacy" to "I want to be more intimate with you."),
        turns("Preference + occupation cross-reference", "about" to "What do you do for work?", "general_chat" to "Do you enjoy that kind of work?", "perspective_of" to "Would you want to keep doing this long-term?"),
        turns("Social behavior continuity", "perspective_of" to "Are you more introverted or extroverted?", "friendship" to "Does that affect how you make friends?"),
        turns("Conflict continuity", "friendship" to "How do you handle conflict with friends?", "romantic_conversation" to "And in a romantic relationship?"),
        turns("Skill transition: general -> romantic -> intimacy -> general", "general_chat" to "Hey, how are you?", "romantic_conversation" to "I've really enjoyed getting to know you.", "romantic_intimacy" to "I want to be closer to you tonight.", "general_chat" to "Anyway — what are your plans for tomorrow?"),
    )

    private fun turns(label: String, vararg pairs: Pair<String, String>) = PersonaContextRealBenchmarkTest.MultiTurnScenario(
        turns = pairs.map { PersonaContextRealBenchmarkTest.MultiTurnScenario.Turn(it.first, it.second) },
    )
}
