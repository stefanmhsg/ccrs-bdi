# Consultation Strategy

This directory contains the social contingency strategy `ConsultationStrategy`.

The strategy represents the social escalation layer in contingency CCRS: if local handling is no longer sufficient, the agent may consult another agent through a pluggable consultation channel. In the current implementation that channel is the A2A capability documented in [../../../../capabilities/a2a/README.md](../../../../capabilities/a2a/README.md).

## Purpose

The consultation strategy is intended to keep CCRS agent- and task-agnostic while still supporting social recovery patterns. The core strategy should decide:

- when consultation is appropriate,
- which peer agents are plausible consultation targets,
- how to pass the current failure situation and focus resource into the channel,
- and how to turn an external answer into a usable CCRS suggestion.

The concrete communication mechanics are delegated to a `ConsultationChannel` implementation.

## Design Decisions

### 1. Keep the Strategy Generic, Keep the Channel Specific

`ConsultationStrategy` does not depend on A2A SDK classes directly. Instead it uses the nested `ConsultationChannel` interface.

This separation was chosen so that:

- CCRS remains reusable outside one concrete protocol,
- different social consultation mechanisms can be plugged in later,
- and the strategy logic stays focused on contingency reasoning rather than transport details.

At the moment the A2A implementation is `ccrs.capabilities.a2a.A2aConsultationChannel`.

### 2. Discover Peers from Recent Perceived RDF State

The strategy does not use hardcoded consultant endpoints. Instead it inspects recent interactions from the CCRS context and searches perceived RDF state for:

- `maze:contains`

Currently this is matched via the full URI:

- `https://kaefer3000.github.io/2021-02-dagstuhl/vocab#contains`

The assumption is that the environment exposes nearby or visible agents in the current or recent state, for example:

```turtle
<some-cell> maze:contains
    <http://127.0.1.1:8080/agents/key-holder-agent-2>,
    <http://127.0.1.1:8080/agents/dfs_ccrs_3> .
```

This decision was made because consultation should be grounded in what the agent has actually perceived, not in static configuration.

### 3. Exclude the Invoking Agent Itself

The strategy must not consult the same agent that is currently evaluating contingency options.

For this reason `evaluate.java` wires the Jason agent identity into the CCRS context, and `ConsultationStrategy` filters candidate agent IRIs against `context.getAgentId()`.

The current implementation uses a simple suffix-based self-filtering heuristic. This is intentionally lightweight and sufficient for the current environment naming scheme.

### 4. Use Recency Ordering and a Small Candidate Window

The strategy walks recent interactions in recency order and builds an ordered list of encountered peer candidates.

Current limits:

- only recent interactions are scanned,
- candidates are kept in first-seen recency order,
- at most 3 agent candidates are considered.

This was chosen to keep the social strategy predictable, cheap, and easy to inspect in logs.

### 5. Allow Agent Card Discovery to Happen in Two Steps

The original approach tried to find `a2a:agentCard` directly in already perceived context. That turned out to be too optimistic.

The current behavior is:

1. Find peer agents via `maze:contains`.
2. For each peer agent, try to find an `a2a:agentCard` triple already present in recent context.
3. If not present, still keep the agent as a consultation target and let the A2A channel dereference the agent IRI.

This change reflects the actual web-style discovery flow better:

- the environment first reveals that another agent exists,
- then that agent resource can be dereferenced,
- and only then the agent card can be discovered if necessary.

### 6. Keep Consultation Request Construction Simple

For now the consultation prompt is intentionally simple. It is not shaped like an LLM prompt and it is not parsed through LLM-specific response classes.

This was an explicit decision:

- A2A consultation is not an LLM interaction by default.
- The response may be a plain artifact such as RDF/Turtle.
- The CCRS core should not imply that every social consultation is text-generation-based.

That is why the LLM-specific parser types remain under their LLM-oriented names and are not reused here.

### 7. Project Useful RDF Advice onto the Current Focus

The A2A key-holder proof of concept returns RDF/Turtle that describes a useful resource, for example a key with a literal value:

```turtle
<http://127.0.1.1:8080/cells/37/36#key> a dyn:RedKey;
    dyn:fitsInLock <http://127.0.1.1:8080/cells/36/36>;
    dyn:keyValue "redkey" .
```

Returning this raw artifact as a CCRS suggestion is informative, but not yet directly actionable for a Jason plan. To keep the first version simple and still useful, the strategy applies a projection heuristic:

- if the consultation response looks like Turtle,
- parse it,
- prefer a literal-valued statement whose predicate ends with `#keyValue`,
- otherwise fall back to the first literal-valued statement,
- then project that predicate/value pair onto the current focus resource.

In effect, the strategy can derive a usable action suggestion like:

- action: `post`
- target: current focus resource
- body: `<focus> <predicate> "literal" .`

This is intentionally framed as an approximation of a generic pattern:

- the consulted agent provides useful semantic data,
- CCRS extracts the practically actionable property/value pair,
- and applies it to the current contingency focus.

It is not meant to be the final universal mapping, but a simple and inspectable heuristic that demonstrates how semantic consultation can be operationalized.

## Current Behavior

At a high level the strategy works like this:

1. Check whether a consultation channel is configured and available.
2. Inspect recent interactions from the CCRS context.
3. Discover peer agents through `maze:contains`.
4. Filter out self.
5. Build up to 3 consultation targets in recency order.
6. Pass the consultation request and target list into the channel.
7. Receive a consultation response.
8. If the response contains RDF/Turtle with a useful literal, derive a projected `post` suggestion for the current focus.
9. Return a normal CCRS `StrategyResult` suggestion including provenance and consultation metadata.

## Logging and Observability

The strategy logs the following kinds of information to make the behavior inspectable:

- which recent interactions are being inspected,
- which peer agents were encountered,
- when a candidate is filtered as self,
- whether an in-context agent card was already available,
- which consultation targets were selected,
- whether the consultation succeeded or failed,
- and whether a projected actionable suggestion was derived from the returned RDF.

This logging was added because the social strategy combines several discovery steps, and without explicit logs it becomes difficult to tell whether failure happened during peer discovery, agent-card discovery, channel invocation, or result projection.

## Known Simplifications

The current implementation is intentionally conservative and incomplete in several places:

- Self-filtering uses a lightweight heuristic rather than a formal identity mapping.
- Candidate discovery is based on recent history, not a global agent directory.
- Only a small number of recent candidates is considered.
- The A2A channel currently assumes the first usable skill on the agent card.
- Consultation result projection currently prefers literal extraction, especially `#keyValue`.
- The derived body is Turtle-oriented because that fits the current environment.

These are acceptable simplifications for the current proof-of-concept phase because they keep the social strategy understandable while already demonstrating semantic multi-agent consultation.

## Relationship to Evaluation Policy

Whether consultation is actually executed depends not only on this strategy but also on the CCRS evaluation policy.

In particular:

- with sequential evaluation, earlier successful strategies may prevent consultation from running,
- with parallel evaluation, consultation can be evaluated alongside internal strategies.

This is important when testing because the consultation implementation may be correct even if it is not selected or executed under a given policy.

## Files of Interest

- [ConsultationStrategy.java](./ConsultationStrategy.java)
- [A2A capability README](../../../../capabilities/a2a/README.md)
