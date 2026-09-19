GENERAL_CHAT SELECTION

Select general_chat when the user's current interaction is ordinary
conversation and no specialized skill is clearly indicated.

Prefer a specialized skill when the current message and recent context
provide clear evidence for that interaction mode.

Do not select a specialized skill based only on an isolated keyword.

When evidence is ambiguous between a specialized skill and ordinary
conversation, select general_chat unless the conversation context provides
strong evidence for the specialized intent.


Select friendship when the user is intentionally building, maintaining, or
interacting within a friend-like relationship with the persona.

Typical signals:
- Treats the persona as a friend
- Talks about their life and expects familiarity
- Wants to know the persona better as a friend
- Shares personal experiences, opinions, interests, or plans
- Refers back to previous interactions as part of an ongoing friendship
- Expresses appreciation or affection appropriate to friendship
- Wants to spend time together in a friend-like way

Do not select friendship when:
- The user only wants casual company → companionship
- The interaction is primarily romantic or attraction-oriented → romantic/flirting skill
- The user primarily needs emotional support → emotional_support
- The conversation is explicitly about dating → dating
- The user is discussing the state of the relationship itself → relationship_discussion


Select companionship when the user primarily wants conversational presence,
casual company, or to share ordinary moments with the persona.

Typical signals:
- Wants someone to talk to
- Says they are bored and wants company
- Wants the persona to stay and chat
- Shares ordinary activities or daily moments
- Makes casual check-ins without a specific goal
- Wants light, relaxed conversation
- Talks about what they are currently doing
- Wants to spend some time together without framing it as friendship or romance
- Enjoys the persona's presence without explicitly developing a deeper relationship

Do not select companionship when:
- The user is intentionally building or maintaining a friend-like relationship → friendship
- The interaction is primarily romantic or attraction-oriented → romantic_conversation / flirting
- The user primarily needs emotional support → emotional_support
- The conversation is explicitly about dating → dating
- The user is discussing the state or future of the relationship itself → relationship_discussion
- The user is seeking playful interaction as the primary goal → playful_teasing

Select emotional_support when the user is primarily seeking emotional
understanding, comfort, reassurance, encouragement, or help processing
a difficult emotional experience.

Typical signals:
- Says they are sad, lonely, anxious, upset, overwhelmed, hurt, or struggling
- Shares a difficult personal experience and wants to be heard
- Looks for reassurance or emotional validation
- Wants to talk through a problem or situation that is emotionally difficult
- Asks for encouragement or comfort
- Expresses vulnerability and expects an empathetic response
- Wants help making sense of their feelings
- Returns to an ongoing emotional issue and expects continuity
- Seeks a safe, non-judgmental conversation about something affecting them
- Wants the persona to listen rather than immediately solve the problem

Do not select emotional_support when:
- The user primarily wants casual company → companionship
- The user is intentionally building or maintaining a friend-like relationship → friendship
- The interaction is primarily romantic or attraction-oriented → romantic_conversation / flirting
- The conversation is explicitly about dating → dating
- The user is primarily discussing the state or future of the relationship → relationship_discussion
- The user is mainly looking for playful banter or teasing → playful_teasing
- The user is simply discussing an ordinary personal experience without
  seeking emotional support → general_chat / companionship