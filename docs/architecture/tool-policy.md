# Tool Policy

Every tool — static, MCP, or Bridge — passes through `ToolExecutor` with
a local policy check. Remote annotations (MCP server claims) are hints,
not authority.

## Policy layers (innermost wins)

1. **Built-in `ToolRisk`**: READ_ONLY → REMOTE_COST → WRITE_LOCAL → WRITE_REMOTE → PRIVACY → DESTRUCTIVE
2. **Incognito gate**: when `memoryEnabled=false`, risk >= WRITE_LOCAL is blocked
3. **User tool policy**: enabled/disabled, allowed contexts, minimum confirmation, cost ceiling, app/domain/path scopes
4. **Approval scope**: per-run, per-step, per-tool — never blanket

## Rules

1. Remote MCP risk annotations can only tighten, never loosen, local classification.
2. "Always for this scope" creates a policy proposal, not an auto-grant. **`ToolPolicy.approvalExpiryMs` is not enforced.** Grants live in `ToolContext.approvedRemoteCostTools` / `confirmedTools`, which are `Set<String>` with no grant timestamps, so there is nothing for an expiry to measure against. The field is listed in `DeadConfigFieldTest.knownDead` with that reason; enforcing it means changing those sets to carry grant times. Until then a confirmation granted at the start of a conversation lasts the whole conversation.
3. **`ToolPolicy.costCeiling` is not enforced.** `PolicyResult.CostExceeded` exists and `ToolExecutor` has a branch for it, but nothing ever constructs one: cost is not known before a tool runs and the app holds no per-tool cost estimate to compare a ceiling against. The field is listed in `DeadConfigFieldTest.knownDead` with that reason. If a ceiling is ever wired, it has to be checked before the API call, not after — which is what this line used to assert had already happened.
4. Shell tools use argv/cwd/env structures — never string interpolation from model output.
5. Accessibility actions require a fresh UI snapshot — stale nodes are rejected.
6. Destructive/irreversible actions always require explicit user confirmation.
7. Policy is stored in DataStore (non-secret) + SecureDataStore (secrets), never in tool definitions.