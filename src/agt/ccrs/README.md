ccrs/
├── core/
│   ├── RdfTriple.java              # Lightweight triple (no dependencies)
│   ├── CcrsVocabulary.java         # Centralized vocabulary definitions
│   ├── OpportunisticCcrs.java      # Interface
│   └── VocabularyMatcher.java      # Pattern matching logic
│
├── jason/
│   ├── CcrsAgent.java              # BRF override
│   └── JasonRdfAdapter.java        # Literal ↔ RdfTriple conversion
│
└── jaca/
    └── CcrsAgentArch.java          # Observable property handling


┌─────────────────────────────────────────────┐
│  Vocabulary Sources (Priority Order)        │
├─────────────────────────────────────────────┤
│ 1. Runtime Registry (Agent-specific)        │
│ 2. Local Files (Project-specific)           │
│ 3. Remote Repositories (Community-shared)   │
│ 4. Core Defaults (Built-in)                 │
└─────────────────────────────────────────────┘