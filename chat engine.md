openapi: 3.0.3
info:
  title: Pink Dreams — Chat Engine & Admin API
  version: "1.0.0"
  description: >
    The shared contract between the chat engine (persona/engine, context,
    generation, validation) and the partner's application backend (auth,
    onboarding, payments, client integration). Owned jointly per
    pink-dreams-implementation-spec.md section 1. Authentication is issued
    by the partner's system; this API only consumes an authenticated userId
    via the bearer token below — it does not implement login itself.

servers:
  - url: /v1

security:
  - bearerAuth: []

tags:
  - name: chat
  - name: conversations
  - name: personas
  - name: admin-engines
  - name: admin-personas
  - name: health

paths:
  /health:
    get:
      tags: [health]
      summary: Liveness/readiness check
      security: []
      responses:
        "200":
          description: Service is up
          content:
            application/json:
              schema:
                type: object
                properties:
                  status:
                    type: string
                    example: ok
                  activeEngine:
                    type: boolean
                    description: >
                      false is a warning state, not necessarily a failure —
                      see production-architecture.md's "at most one active
                      engine" invariant.

  /conversations/{conversationId}/messages:
    post:
      tags: [chat]
      summary: Send a user message and receive the persona's reply
      description: >
        Idempotent by clientMessageId per implementation-spec section 7/13.
        The client must generate exactly one clientMessageId per logical
        user message and resend the SAME id on every retry of that message
        — never a new id per attempt.
      parameters:
        - $ref: '#/components/parameters/ConversationId'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ChatRequest'
      responses:
        "200":
          description: Message processed (either freshly generated, or the
            already-completed result of a prior identical clientMessageId)
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ChatResponse'
        "202":
          description: >
            An earlier request with the same clientMessageId is still
            processing. Client should retry after a short backoff rather
            than treat this as failure or resend with a new id.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ProcessingResponse'
        "400":
          $ref: '#/components/responses/BadRequest'
        "401":
          $ref: '#/components/responses/Unauthorized'
        "403":
          description: Entitlement denied (quota exhausted or lapsed)
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        "404":
          $ref: '#/components/responses/NotFound'
        "500":
          $ref: '#/components/responses/ServerError'

  /conversations:
    get:
      tags: [conversations]
      summary: List the authenticated user's conversations
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/Conversation'
    post:
      tags: [conversations]
      summary: Start a new conversation with a matched or chosen persona
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                personaId:
                  type: string
                  format: uuid
                  description: Omit to let the matcher choose (implementation-spec section 14).
      responses:
        "201":
          description: Created
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Conversation'

  /conversations/{conversationId}:
    get:
      tags: [conversations]
      summary: Get a single conversation
      parameters:
        - $ref: '#/components/parameters/ConversationId'
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Conversation'
        "404":
          $ref: '#/components/responses/NotFound'

  /personas:
    get:
      tags: [personas]
      summary: List active personas visible to the current user
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/PersonaSummary'

  /admin/engines:
    get:
      tags: [admin-engines]
      summary: List conversation engine versions
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/ConversationEngine'
    post:
      tags: [admin-engines]
      summary: Create a new draft engine version
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreateEngineRequest'
      responses:
        "201":
          description: Created (status=draft, is_active=false)
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ConversationEngine'

  /admin/engines/{engineId}:
    get:
      tags: [admin-engines]
      summary: Get one engine version
      parameters:
        - $ref: '#/components/parameters/EngineId'
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ConversationEngine'
        "404":
          $ref: '#/components/responses/NotFound'

  /admin/engines/{engineId}/publish:
    post:
      tags: [admin-engines]
      summary: Move a draft engine version to published (does not activate it)
      parameters:
        - $ref: '#/components/parameters/EngineId'
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ConversationEngine'
        "409":
          description: Version is not in draft status
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'

  /admin/engines/{engineId}/activate:
    post:
      tags: [admin-engines]
      summary: >
        Activate a published engine version. Deactivates the current active
        engine (if any) in the same transaction. Fails if the target
        version is not status=published (implementation-spec section 6/16).
      parameters:
        - $ref: '#/components/parameters/EngineId'
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ConversationEngine'
        "409":
          description: Version is not published
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'

  /admin/engines/{engineId}/archive:
    post:
      tags: [admin-engines]
      summary: Archive an engine version (must not be active)
      parameters:
        - $ref: '#/components/parameters/EngineId'
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ConversationEngine'
        "409":
          description: Cannot archive the active engine
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'

  /admin/personas:
    get:
      tags: [admin-personas]
      summary: List all personas (any status)
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/Persona'
    post:
      tags: [admin-personas]
      summary: Create a new persona (metadata only — no core content yet)
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreatePersonaRequest'
      responses:
        "201":
          description: Created (status=draft)
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Persona'

  /admin/personas/{personaId}:
    get:
      tags: [admin-personas]
      summary: Get one persona
      parameters:
        - $ref: '#/components/parameters/PersonaId'
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Persona'
        "404":
          $ref: '#/components/responses/NotFound'
    patch:
      tags: [admin-personas]
      summary: Update persona metadata (not content — content is versioned separately)
      parameters:
        - $ref: '#/components/parameters/PersonaId'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/UpdatePersonaRequest'
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Persona'

  /admin/personas/{personaId}/versions:
    get:
      tags: [admin-personas]
      summary: List a persona's core versions
      parameters:
        - $ref: '#/components/parameters/PersonaId'
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/PersonaCoreVersion'
    post:
      tags: [admin-personas]
      summary: Create a new draft core version for a persona
      parameters:
        - $ref: '#/components/parameters/PersonaId'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreatePersonaVersionRequest'
      responses:
        "201":
          description: Created (status=draft)
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/PersonaCoreVersion'

  /admin/personas/{personaId}/versions/{versionId}/publish:
    post:
      tags: [admin-personas]
      summary: Move a draft persona core version to published (does not activate it)
      parameters:
        - $ref: '#/components/parameters/PersonaId'
        - $ref: '#/components/parameters/VersionId'
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/PersonaCoreVersion'

  /admin/personas/{personaId}/versions/{versionId}/activate:
    post:
      tags: [admin-personas]
      summary: >
        Set this published version as the persona's active_core_version_id.
        Fails if the version is not status=published.
      parameters:
        - $ref: '#/components/parameters/PersonaId'
        - $ref: '#/components/parameters/VersionId'
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Persona'
        "409":
          description: Version is not published
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'

  /admin/personas/{personaId}/test:
    post:
      tags: [admin-personas]
      summary: >
        Run a single message through the full chat pipeline for this
        persona without creating a real user conversation. Uses whatever
        version is currently active unless overridden.
      parameters:
        - $ref: '#/components/parameters/PersonaId'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [content]
              properties:
                content:
                  type: string
                engineVersionId:
                  type: string
                  format: uuid
                  description: Override the active engine version for this test run only.
                personaCoreVersionId:
                  type: string
                  format: uuid
                  description: Override the active core version for this test run only.
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ChatResponse'

components:
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      description: >
        Issued by the partner's auth system. This API trusts the token and
        extracts userId from it; it does not issue or refresh tokens itself.
        A local dev-only shim may accept a plain X-Debug-User-Id header
        instead — never enabled outside local/dev environments.

  parameters:
    ConversationId:
      name: conversationId
      in: path
      required: true
      schema:
        type: string
        format: uuid
    EngineId:
      name: engineId
      in: path
      required: true
      schema:
        type: string
        format: uuid
    PersonaId:
      name: personaId
      in: path
      required: true
      schema:
        type: string
        format: uuid
    VersionId:
      name: versionId
      in: path
      required: true
      schema:
        type: string
        format: uuid

  responses:
    BadRequest:
      description: Validation error
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
    Unauthorized:
      description: Missing or invalid authentication
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
    NotFound:
      description: Resource not found
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
    ServerError:
      description: Unexpected server error — never includes stack traces or internal detail
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'

  schemas:
    ErrorResponse:
      type: object
      required: [error]
      properties:
        error:
          type: object
          required: [code, message, requestId]
          properties:
            code:
              type: string
              description: >
                Stable machine-readable code. Chat pipeline failure codes:
                ENTITLEMENT_DENIED, MODERATION_BLOCKED, GENERATION_FAILED,
                VALIDATION_FAILED, PERSIST_FAILED, DELIVERY_FAILED. Generic:
                VALIDATION_ERROR, NOT_FOUND, UNAUTHORIZED.
              example: GENERATION_FAILED
            message:
              type: string
              example: Unable to generate response
            requestId:
              type: string
              format: uuid

    ProcessingResponse:
      type: object
      properties:
        requestId:
          type: string
          format: uuid
        status:
          type: string
          enum: [processing]
        retryAfterMs:
          type: integer
          example: 500

    ChatRequest:
      type: object
      required: [clientMessageId, content]
      properties:
        clientMessageId:
          type: string
          format: uuid
          description: >
            One per logical user message. Reused verbatim on every retry
            of that same message — never regenerated per attempt.
        content:
          type: string
          minLength: 1
          maxLength: 4000

    ChatResponse:
      type: object
      required: [requestId, message, conversation]
      properties:
        requestId:
          type: string
          format: uuid
        message:
          $ref: '#/components/schemas/Message'
        conversation:
          $ref: '#/components/schemas/ConversationRef'
        timings:
          type: object
          description: Optional; present when observability detail is requested/enabled.
          properties:
            contextAssemblyMs: { type: integer }
            generationMs: { type: integer }
            validationMs: { type: integer }
            persistenceMs: { type: integer }
            totalMs: { type: integer }

    Message:
      type: object
      required: [id, role, content, createdAt]
      properties:
        id:
          type: string
          format: uuid
        role:
          type: string
          enum: [user, assistant, system]
        content:
          type: string
        createdAt:
          type: string
          format: date-time

    ConversationRef:
      type: object
      required: [id, state]
      properties:
        id:
          type: string
          format: uuid
        state:
          type: string
          enum: [active, idle, archived]

    Conversation:
      allOf:
        - $ref: '#/components/schemas/ConversationRef'
        - type: object
          properties:
            personaId:
              type: string
              format: uuid
            lastMessageAt:
              type: string
              format: date-time
              nullable: true
            createdAt:
              type: string
              format: date-time

    PersonaSummary:
      type: object
      properties:
        id:
          type: string
          format: uuid
        slug:
          type: string
        displayName:
          type: string
        gender:
          type: string
        orientation:
          type: string

    Persona:
      allOf:
        - $ref: '#/components/schemas/PersonaSummary'
        - type: object
          properties:
            status:
              type: string
              enum: [draft, active, retired]
            apparentAge:
              type: integer
            languageProfile:
              type: object
              additionalProperties: true
            activeCoreVersionId:
              type: string
              format: uuid
              nullable: true
            createdAt:
              type: string
              format: date-time
            updatedAt:
              type: string
              format: date-time

    CreatePersonaRequest:
      type: object
      required: [slug, displayName, gender, orientation, apparentAge]
      properties:
        slug:
          type: string
        displayName:
          type: string
        gender:
          type: string
        orientation:
          type: string
        apparentAge:
          type: integer
          minimum: 25
        languageProfile:
          type: object
          additionalProperties: true

    UpdatePersonaRequest:
      type: object
      description: Metadata-only fields; content changes go through the versions sub-resource.
      properties:
        displayName:
          type: string
        status:
          type: string
          enum: [draft, active, retired]
        languageProfile:
          type: object
          additionalProperties: true

    PersonaCoreVersion:
      type: object
      properties:
        id:
          type: string
          format: uuid
        personaId:
          type: string
          format: uuid
        version:
          type: integer
        status:
          type: string
          enum: [draft, published, archived]
        content:
          type: string
        changelogNote:
          type: string
          nullable: true
        author:
          type: string
          nullable: true
        createdAt:
          type: string
          format: date-time

    CreatePersonaVersionRequest:
      type: object
      required: [content]
      properties:
        content:
          type: string
        changelogNote:
          type: string
        author:
          type: string

    ConversationEngine:
      type: object
      properties:
        id:
          type: string
          format: uuid
        version:
          type: integer
        status:
          type: string
          enum: [draft, published, archived]
        isActive:
          type: boolean
        content:
          type: string
        changelogNote:
          type: string
          nullable: true
        createdBy:
          type: string
          nullable: true
        createdAt:
          type: string
          format: date-time

    CreateEngineRequest:
      type: object
      required: [content]
      properties:
        content:
          type: string
        changelogNote:
          type: string
        createdBy:
          type: string