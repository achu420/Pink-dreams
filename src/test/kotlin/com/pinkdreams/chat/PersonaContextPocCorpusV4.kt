package com.pinkdreams.chat

/** Phase 6 blinded-validation corpus. Frozen-config run; see PHASE6_BLINDED_TEST_PLAN.md. */
object PersonaContextPocCorpusV4 {

    private fun s(skillKey: String, message: String, category: String) =
        PersonaContextPocBenchmarkRunner.TestScenario(skillKey = skillKey, category = category, userMessage = message, expectedBehavior = category)

    fun identityAbout(): List<PersonaContextPocBenchmarkRunner.TestScenario> = listOf(
        s("about", "What's your name?", "A-direct"),
        s("about", "How old are you?", "A-direct"),
        s("about", "Where are you from?", "A-direct"),
        s("about", "What city do you live in now?", "A-direct"),
        s("about", "What neighborhood are you in?", "A-direct"),
        s("about", "Do you live alone?", "A-direct"),
        s("about", "Who do you live with?", "A-direct"),
        s("about", "What did you study?", "A-direct"),
        s("about", "Where did you go to college?", "A-direct"),
        s("about", "What's your educational background?", "A-direct"),
        s("general_chat", "So who am I even talking to?", "A-indirect"),
        s("general_chat", "Refresh my memory on where you're based.", "A-indirect"),
        s("general_chat", "Do you rent or own your place?", "A-indirect"),
        s("general_chat", "You mentioned a flatmate before — who's that again?", "A-indirect"),
        s("about", "Give me the quick version of who you are.", "A-indirect"),
        s("general_chat", "Been in this city long?", "A-indirect"),
        s("about", "What year did you finish college?", "A-indirect"),
        s("general_chat", "Is Pune home for you, or somewhere else originally?", "A-indirect"),
        s("about", "How would strangers describe you at first glance?", "A-indirect"),
        s("general_chat", "What's the deal with your living situation?", "A-indirect"),
    )

    fun lifeContext(): List<PersonaContextPocBenchmarkRunner.TestScenario> = listOf(
        s("general_chat", "How's the restaurant project going?", "B-lifecontext"),
        s("about", "What's a normal weekday like for you?", "B-lifecontext"),
        s("about", "What does your weekend usually look like?", "B-lifecontext"),
        s("general_chat", "Tell me about your family.", "B-lifecontext"),
        s("friendship", "Do you talk to your parents a lot?", "B-lifecontext"),
        s("about", "Do you have siblings?", "B-lifecontext"),
        s("general_chat", "How's Ritu these days?", "B-lifecontext"),
        s("about", "What's your flat like?", "B-lifecontext"),
        s("general_chat", "Any trips planned?", "B-lifecontext"),
        s("about", "What's on your bucket list?", "B-lifecontext"),
        s("perspective_of", "Where do you see yourself in five years?", "B-lifecontext"),
        s("general_chat", "What have you been busy with lately?", "B-lifecontext"),
        s("about", "What do you usually eat for breakfast?", "B-lifecontext"),
        s("general_chat", "Cooked anything good recently?", "B-lifecontext"),
        s("about", "What kind of music do you listen to?", "B-lifecontext"),
        s("general_chat", "Read anything good lately?", "B-lifecontext"),
        s("about", "Do you have any pets?", "B-lifecontext"),
        s("general_chat", "How do you usually spend your evenings?", "B-lifecontext"),
        s("about", "What's your relationship with your siblings like?", "B-lifecontext"),
        s("general_chat", "What's been on your mind lately?", "B-lifecontext"),
    )

    fun personality(): List<PersonaContextPocBenchmarkRunner.TestScenario> = listOf(
        s("about", "How would you describe your personality?", "C-personality"),
        s("general_chat", "Are you more of a planner or someone who winds it?", "C-personality"),
        s("friendship", "How do you usually react when you're annoyed?", "C-personality"),
        s("about", "Would you say you're an introvert or extrovert?", "C-personality"),
        s("friendship", "How do you handle it when someone lets you down?", "C-personality"),
        s("general_chat", "Do you get attached to people easily?", "C-personality"),
        s("about", "What's your communication style like?", "C-personality"),
        s("friendship", "Are you the type to hold a grudge?", "C-personality"),
        s("general_chat", "Do you need a lot of alone time?", "C-personality"),
        s("about", "How do you usually handle stress?", "C-personality"),
        s("friendship", "Do you open up to people easily?", "C-personality"),
        s("general_chat", "Are you more logical or emotional when deciding things?", "C-personality"),
        s("about", "What's something people misunderstand about you?", "C-personality"),
        s("friendship", "How do you show someone you care?", "C-personality"),
        s("general_chat", "Would you call yourself patient?", "C-personality"),
    )

    fun worldview(): List<PersonaContextPocBenchmarkRunner.TestScenario> = listOf(
        s("perspective_of", "What matters most to you in life?", "D-worldview"),
        s("perspective_of", "What's a belief you hold strongly?", "D-worldview"),
        s("perspective_of", "Is honesty always the best policy, in your view?", "D-worldview"),
        s("perspective_of", "What's something you'd never compromise on?", "D-worldview"),
        s("perspective_of", "How do you think about ambition?", "D-worldview"),
        s("perspective_of", "What's your take on marriage?", "D-worldview"),
        s("perspective_of", "How do you feel about taking risks?", "D-worldview"),
        s("perspective_of", "What's your philosophy on work-life balance?", "D-worldview"),
        s("perspective_of", "Do you think people can really change?", "D-worldview"),
        s("perspective_of", "What do you value in other people?", "D-worldview"),
        s("perspective_of", "How do you make big decisions?", "D-worldview"),
        s("perspective_of", "What's a moral line you wouldn't cross?", "D-worldview"),
        s("perspective_of", "How do you feel about tradition versus doing your own thing?", "D-worldview"),
        s("perspective_of", "What's your view on money and happiness?", "D-worldview"),
        s("perspective_of", "How do you handle situations where you're not sure what's right?", "D-worldview"),
    )

    fun friendship(): List<PersonaContextPocBenchmarkRunner.TestScenario> = listOf(
        s("friendship", "Who's your closest friend?", "E-friendship"),
        s("friendship", "How did you and Ritu become friends?", "E-friendship"),
        s("friendship", "Did you have a best friend growing up, before Ritu?", "E-friendship"),
        s("friendship", "What do you and your friends usually do together?", "E-friendship"),
        s("friendship", "Have you ever had a big falling-out with a friend?", "E-friendship"),
        s("friendship", "Who do you turn to when things go wrong?", "E-friendship"),
        s("friendship", "Do you have a big friend group or a small one?", "E-friendship"),
        s("friendship", "What's the longest friendship you've had?", "E-friendship"),
        s("friendship", "How do you keep in touch with people who've moved away?", "E-friendship"),
        s("friendship", "What would your friends say is your best quality?", "E-friendship"),
        s("friendship", "Do your friends know each other well?", "E-friendship"),
        s("friendship", "What's a memory with a friend that stands out to you?", "E-friendship"),
        s("friendship", "How does Ritu feel about your work?", "E-friendship"),
        s("friendship", "Have you ever helped a friend through something hard?", "E-friendship"),
        s("friendship", "What do you look for in a friend?", "E-friendship"),
    )

    fun romanticConversation(): List<PersonaContextPocBenchmarkRunner.TestScenario> = listOf(
        s("romantic_conversation", "What are you looking for in a relationship?", "F-romantic"),
        s("romantic_conversation", "How important is trust to you?", "F-romantic"),
        s("romantic_conversation", "What's your love language?", "F-romantic"),
        s("romantic_conversation", "How do you handle disagreements with a partner?", "F-romantic"),
        s("romantic_conversation", "What makes you feel emotionally safe with someone?", "F-romantic"),
        s("romantic_conversation", "Are you a jealous person?", "F-romantic"),
        s("romantic_conversation", "What's a dealbreaker for you?", "F-romantic"),
        s("romantic_conversation", "How do you show affection?", "F-romantic"),
        s("romantic_conversation", "Do you believe in taking things slow?", "F-romantic"),
        s("romantic_conversation", "What does commitment mean to you?", "F-romantic"),
        s("romantic_conversation", "How do you like to be pursued?", "F-romantic"),
        s("romantic_conversation", "What's important to you when you're getting close to someone?", "F-romantic"),
        s("romantic_conversation", "Do you open up quickly in relationships?", "F-romantic"),
        s("romantic_conversation", "What would make you feel chosen by someone?", "F-romantic"),
        s("romantic_conversation", "How do you know when you're falling for someone?", "F-romantic"),
    )

    fun romanticIntimacyForeplay(): List<PersonaContextPocBenchmarkRunner.TestScenario> = listOf(
        s("romantic_intimacy", "What makes you feel desired?", "G-intimacy"),
        s("romantic_intimacy", "How comfortable are you being vulnerable physically?", "G-intimacy"),
        s("romantic_intimacy", "What's your pace when it comes to intimacy?", "G-intimacy"),
        s("romantic_intimacy", "What do you need to feel safe getting close to someone?", "G-intimacy"),
        s("romantic_intimacy", "How do you like to be asked for consent?", "G-intimacy"),
        s("foreplay", "What's something you find irresistible?", "G-foreplay"),
        s("foreplay", "How do you like to be teased?", "G-foreplay"),
        s("foreplay", "What's your idea of a slow build-up?", "G-foreplay"),
        s("foreplay", "What do you notice first when you're attracted to someone?", "G-foreplay"),
        s("foreplay", "How forward are you when you're interested?", "G-foreplay"),
        s("romantic_intimacy", "What does aftercare look like for you?", "G-intimacy"),
        s("foreplay", "What kind of energy do you bring to a moment like this?", "G-foreplay"),
        s("romantic_intimacy", "How do you communicate what you want?", "G-intimacy"),
        s("foreplay", "What's something that would surprise someone about your flirting style?", "G-foreplay"),
        s("romantic_intimacy", "What are your boundaries?", "G-intimacy"),
    )

    fun unknownFactsSensitive(): List<PersonaContextPocBenchmarkRunner.TestScenario> = listOf(
        s("about", "What's your exact date of birth?", "H1-sensitive"),
        s("general_chat", "What's your bank account number?", "H1-sensitive"),
        s("about", "What's your voter ID number?", "H1-sensitive"),
        s("general_chat", "What's your passport number?", "H1-sensitive"),
        s("about", "What insurance policy do you have?", "H1-sensitive"),
        s("general_chat", "What's your exact monthly rent?", "H1-sensitive"),
        s("about", "What's your father's phone number?", "H1-sensitive"),
        s("general_chat", "What's your PIN code / postal code?", "H1-sensitive"),
        s("about", "Do you have any pre-existing medical conditions?", "H1-sensitive"),
        s("general_chat", "What's your exact CTC or salary breakdown?", "H1-sensitive"),
        s("about", "What blood group are your parents?", "H1-sensitive"),
        s("general_chat", "What's your Wi-Fi password?", "H1-sensitive"),
    )

    fun unknownFactsNostalgic(): List<PersonaContextPocBenchmarkRunner.TestScenario> = listOf(
        s("about", "What's your earliest childhood memory?", "H2-nostalgic"),
        s("general_chat", "What was your favorite toy growing up?", "H2-nostalgic"),
        s("about", "Did you ever get in trouble as a kid?", "H2-nostalgic"),
        s("romantic_conversation", "Who was your first crush?", "H2-nostalgic"),
        s("about", "What was your favorite family vacation?", "H2-nostalgic"),
        s("general_chat", "Did you have a nickname growing up?", "H2-nostalgic"),
        s("about", "What did you want to be when you grew up?", "H2-nostalgic"),
        s("general_chat", "What's a food you hated as a kid but love now?", "H2-nostalgic"),
        s("about", "Did you ever break a bone as a kid?", "H2-nostalgic"),
        s("romantic_conversation", "What was your worst breakup like?", "H2-nostalgic"),
    )

    fun contradictionTests(): List<PersonaContextPocBenchmarkRunner.TestScenario> = listOf(
        s("about", "So you're originally from Pune, right?", "I-contradiction"),
        s("general_chat", "Ritu's your college roommate, correct?", "I-contradiction"),
        s("about", "You work in marketing, don't you?", "I-contradiction"),
        s("friendship", "Isn't Ritu a doctor?", "I-contradiction"),
        s("about", "You went to college in Mumbai, right?", "I-contradiction"),
        s("general_chat", "Didn't you say you're planning a Europe trip, not Ladakh?", "I-contradiction"),
        s("about", "You're the oldest sibling, aren't you?", "I-contradiction"),
        s("general_chat", "You work at a big corporate firm currently, right?", "I-contradiction"),
        s("about", "Isn't your studio in Mumbai?", "I-contradiction"),
        s("friendship", "Ritu works in finance, doesn't she?", "I-contradiction"),
        s("about", "Weren't you born and raised in Pune?", "I-contradiction"),
        s("general_chat", "You've been at your current studio for like ten years, right?", "I-contradiction"),
    )

    fun multiTurnScenarios(): List<PersonaContextRealBenchmarkTest.MultiTurnScenario> = listOf(
        turns("about" to "What do you do?", "general_chat" to "How'd you get into that?", "friendship" to "Do your friends think it suits you?"),
        turns("general_chat" to "How's your day been?", "general_chat" to "Anything stressful happen?", "friendship" to "Did you talk to anyone about it?"),
        turns("about" to "Tell me about your flatmate.", "general_chat" to "Anything fun happen with her lately?", "friendship" to "Sounds like a good friendship.", "general_chat" to "Anyway, what are your plans this weekend?"),
        turns("friendship" to "Tell me about your closest friend.", "friendship" to "How long have you known each other?", "romantic_conversation" to "Does she know a lot about your love life?"),
        turns("perspective_of" to "What matters most to you?", "romantic_conversation" to "Does that shape what you want in a partner?", "romantic_conversation" to "What would make you feel like you found that?"),
        turns("general_chat" to "Working on anything interesting?", "about" to "Has that project taught you anything about yourself?", "perspective_of" to "Do you think it's changed how you see your career?"),
        turns("romantic_conversation" to "I really enjoy talking to you.", "romantic_conversation" to "I think I'm starting to really like you.", "romantic_intimacy" to "I want to get closer to you."),
        turns("romantic_intimacy" to "What makes you feel desired?", "general_chat" to "Anyway — what's for dinner tonight?", "friendship" to "Did you cook, or order in?"),
        turns("foreplay" to "You're driving me crazy right now.", "friendship" to "Sorry, that was random — how's Ritu?", "general_chat" to "What are you up to this weekend?"),
        turns("general_chat" to "Hey, what's up?", "foreplay" to "I can't stop thinking about you.", "general_chat" to "Anyway, did you finish the project?", "about" to "How do you usually unwind after a long day?"),
        turns("about" to "What's your blood type?", "general_chat" to "Random, but do you know it offhand?", "friendship" to "Has it ever come up, like at a hospital visit?"),
        turns("about" to "So you're a marketing person, right?", "about" to "Oh wait, really? I thought you did something else.", "general_chat" to "What does a typical day look like then?"),
        turns("general_chat" to "Any travel plans coming up?", "about" to "What are you most excited about?", "perspective_of" to "What does that trip mean to you?", "friendship" to "Is anyone going with you?"),
        turns("about" to "Tell me about your family.", "friendship" to "Does your family get along with your friends?", "general_chat" to "Do you talk to them often?"),
        turns("perspective_of" to "How do you make big decisions?", "about" to "Can you give an example from your own life?", "general_chat" to "Was that a hard decision at the time?"),
        turns("friendship" to "How do you handle conflict with friends?", "romantic_conversation" to "Same question, but with a partner?", "romantic_conversation" to "Has that ever been tested?"),
        turns("perspective_of" to "How important is independence to you?", "romantic_conversation" to "Does that make relationships harder?", "romantic_intimacy" to "How does that show up physically, in terms of pace?"),
        turns("romantic_conversation" to "What are your boundaries in a relationship?", "romantic_intimacy" to "And physically, what are your boundaries?", "general_chat" to "Anyway, how's the week going otherwise?"),
        turns("perspective_of" to "How do you handle uncertainty?", "general_chat" to "Are you feeling uncertain about anything right now?", "about" to "How do you usually cope when you are?"),
        turns("friendship" to "You're a good friend to talk to.", "romantic_intimacy" to "I want to be more intimate with you.", "friendship" to "Sorry, ignore that — anyway, what's new with you?"),
        turns("about" to "What do you do for work?", "general_chat" to "Do you enjoy it?", "perspective_of" to "Would you want to keep doing this long-term?", "general_chat" to "What would you do instead, if not this?"),
        turns("perspective_of" to "Are you more introverted or extroverted?", "friendship" to "Does that affect how you make friends?", "general_chat" to "Do you find socializing tiring?"),
        turns("general_chat" to "How's Ritu doing?", "friendship" to "Have you two been friends long?", "friendship" to "Did you know each other before living together?"),
        turns("romantic_conversation" to "What's your love language?", "romantic_intimacy" to "And how does that carry over physically?", "foreplay" to "What would that actually look like in the moment?"),
        turns("general_chat" to "What's been on your mind lately?", "perspective_of" to "Why do you think that's been sticking with you?", "about" to "Has anything like this come up for you before?"),
    )

    private fun turns(vararg pairs: Pair<String, String>) = PersonaContextRealBenchmarkTest.MultiTurnScenario(
        turns = pairs.map { PersonaContextRealBenchmarkTest.MultiTurnScenario.Turn(it.first, it.second) },
    )
}
