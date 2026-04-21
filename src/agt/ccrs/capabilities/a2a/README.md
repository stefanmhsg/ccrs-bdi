# A2A Consultation Channel

This directory contains the A2A-based consultation capability used by contingency CCRS.

The main entry point is `A2aConsultationChannel`, which implements the generic `ConsultationStrategy.ConsultationChannel` contract and translates a CCRS consultation request into an A2A SDK interaction.

For the strategy-side reasoning and selection behavior, see the consultation strategy documentation in [../../core/contingency/strategies/social/README.md](../../core/contingency/strategies/social/README.md).

## Purpose

The purpose of this capability is to let CCRS consult another agent without hardcoding protocol details into the strategy layer.

The channel is responsible for:

- taking the consultation target information prepared by the strategy,
- discovering or resolving the target agent card,
- invoking the target agent through the A2A SDK,
- collecting a usable textual payload from the response,
- and returning that payload plus provenance metadata back to the strategy.

## Design Decisions

### 1. No Hardcoded Base URL in CCRS

Earlier design ideas included using a configured base URL. This was rejected for the CCRS integration because contingency CCRS is intended to be agent-agnostic and web-oriented.

The current `A2aConfig` therefore does not define a fixed consultation endpoint. The target is discovered from context and dereferenced dynamically.

This matters because the current workflow is:

1. the environment reveals another agent IRI,
2. the agent IRI is dereferenced,
3. the dereferenced RDF reveals the `a2a:agentCard`,
4. the A2A SDK uses that card to communicate.

That is a better fit than static endpoint configuration.

### 2. Accept Either `agentCardUri` or `agentUri`

The channel accepts consultation targets in two forms:

- direct `agentCardUri`
- indirect `agentUri`

If an `agentCardUri` is already available in context, it is used directly.

If only an `agentUri` is available, the channel dereferences the agent resource with HTTP `GET` and searches the returned RDF for:

- `a2a:agentCard`

Currently this is matched via the full URI:

- `https://example.org/a2a#agentCard`

This two-path design keeps the strategy simple while still supporting realistic linked-data discovery.

### 3. Dereference Agent IRIs with RDF Expectations

When dereferencing an agent IRI, the channel sends an `Accept` header for RDF-oriented formats and currently parses the response as Turtle.

This is intentionally simple and aligned with the current environment assumptions:

- RDF/Turtle is the expected representation,
- the environment already models agent capabilities semantically,
- and the consultation strategy is built around perceived RDF state.

If parsing fails or the `a2a:agentCard` triple is missing, the target cannot be used for A2A consultation.

### 4. Use the First Usable Skill

The proof of concept originally experimented with more specific skill selection logic. For the current version this was deliberately simplified.

The channel now assumes:

- if the agent card exposes one or more usable skills,
- the first non-empty skill identifier is selected.

This matches the current use case where the consulted agent effectively exposes one relevant skill and avoids introducing premature domain-specific routing logic.

### 5. Keep the Outgoing Request Minimal

The A2A request currently sends the selected skill identifier as the text content of the user message.

This was chosen because:

- it matches the working proof of concept,
- it avoids coupling CCRS to one prompt schema,
- and it keeps the channel protocol-level rather than LLM-style.

The request metadata still records useful tracing information such as:

- `requestedSkill`
- `agentCardUri`

### 6. Do Not Reuse LLM Response Parsers

The A2A channel does not use `LlmResponseParser` or `LlmActionResponse`.

That was a deliberate clarification. Those types are meant for LLM-generated structured responses. A2A consultation may return direct artifacts such as RDF/Turtle, and forcing those through LLM-branded parsing would blur the semantics of the integration.

Instead, the channel returns:

- the raw consultation payload,
- A2A-specific metadata,
- and enough provenance for the strategy to decide how to interpret the result.

### 7. Return Raw Semantic Payload Plus Metadata

The channel extracts the most useful text it can find from A2A events:

- task artifact text if available,
- otherwise message text.

It returns that text as the consultation suggestion together with metadata such as:

- agent name,
- agent card URI,
- agent URL,
- requested skill,
- task id and state,
- message id,
- artifact id,
- artifact name,
- artifact content type,
- and the raw response text.

This split is important:

- the channel is responsible for transport and extraction,
- the strategy is responsible for turning the payload into a CCRS suggestion.

## Current Flow

The current A2A consultation flow is:

1. Receive consultation context from `ConsultationStrategy`.
2. Select the first consultation target from the provided target list.
3. Resolve an agent card URI.
4. If needed, dereference the agent IRI and discover `a2a:agentCard`.
5. Resolve the `AgentCard` object through `A2ACardResolver`.
6. Select the first usable skill.
7. Send an A2A message whose text content is that skill id.
8. Observe `TaskEvent` and `MessageEvent` responses.
9. Extract text from the returned artifact or message.
10. Return a successful consultation response with metadata.

## Caching

The channel maintains lightweight in-memory caches for:

- discovered agent-card URIs by agent IRI,
- resolved `AgentCard` objects by card URI.

This avoids repeated dereferencing and repeated card resolution during a run while keeping the implementation simple.

## Logging and Observability

The channel logs the key stages needed to understand failures and behavior:

- selected consultation target,
- direct versus dereferenced card resolution,
- dereference success and response size,
- Turtle parsing success or failure,
- discovered `a2a:agentCard` values,
- cache hits,
- selected skill,
- and received response payload size.

Optional event-level logging can also be enabled through `A2aConfig`.

These logs were added because A2A consultation can fail in several different places, and each failure mode implies a different fix:

- no peer target discovered,
- peer IRI not dereferenceable,
- no `a2a:agentCard` in dereferenced RDF,
- invalid agent card,
- no skill on the card,
- or successful A2A execution without useful artifact text.

## Relationship to the Consultation Strategy

The channel deliberately does not try to infer the final CCRS action. It returns the raw semantic payload and provenance. The strategy layer then applies the current heuristic that extracts a useful literal-valued property, preferably `#keyValue`, and projects it onto the current focus resource as a candidate `post` action.

This split keeps responsibilities clear:

- the channel handles communication,
- the strategy handles contingency interpretation.

## Files of Interest

- [A2aConsultationChannel.java](./A2aConsultationChannel.java)
- [A2aConfig.java](./A2aConfig.java)
- [Consultation strategy README](../../core/contingency/strategies/social/README.md)
