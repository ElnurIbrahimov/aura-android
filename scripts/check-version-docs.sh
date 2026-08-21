#!/usr/bin/env bash
# Verify that every count README.md and architecture.md state in the present
# tense still matches the source they describe.
#
# Usage: bash scripts/check-version-docs.sh
#
# This runs in the cheap `gates` CI job: no JDK, no SDK, no Gradle. Everything
# below is derived with grep/sed/awk from files in the working tree, because
# that is the only thing available there. The unit-test count is deliberately
# NOT here — it can only be read from a real run's JUnit XML, which is why
# scripts/check-test-count.sh exists and runs in `build-test` instead.
#
# The previous version of this file checked one thing: that the string
# "v<versionName>" appeared *somewhere* in README.md. It passed while the docs
# claimed 76 tools against 78 registrations, 33 ViewModels against 37, 6
# provider classes against 8, 21 secondary routes against 29, and a
# MemoryDatabase version that architecture.md contradicted two sections later.
# Asserting that a number is mentioned is not the same as asserting it is right.
#
# Two rules this file exists to hold:
#
#   1. Every number is DERIVED from source, never written down here. A constant
#      in the gate is the same lie as a constant in the doc, one file further
#      from the code.
#   2. A derivation that yields zero, empty, or a non-number aborts the run.
#      Four source-scanning tests in this repo (ENGINEERING_HISTORY §2.6)
#      reported "no violations" over an empty file list; a gate that cannot fail
#      for the reason it is named after is worse than no gate, because it is
#      also a claim that the thing was checked.
#
# Note on `derive`: it assigns into a named variable rather than being used in
# `$(...)`. Command substitution runs in a subshell, so an `exit` inside one
# only kills the subshell — the FATAL message would be captured into the
# variable and the run would continue with a corrupt value. That is the same
# class of silent pass this script exists to stop.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

BUILD_FILE="app/build.gradle.kts"
README="README.md"
# The root architecture.md is the canonical one. docs/architecture.md used to be
# a second, drifting copy; it is now a pointer at this file.
ARCH="architecture.md"

CORE_MAIN="aura-core/src/main/kotlin"
APP_MAIN="app/src/main/kotlin"

fail=0

err() {
  echo "ERROR: $*"
  fail=1
}

# derive VARNAME "human name" derivation_function
#
# Runs the function, requires a positive integer, and aborts the whole script
# with exit 2 when it does not get one.
derive() {
  local var="$1" name="$2" fn="$3"
  local value
  value=$("$fn")
  if ! [[ "$value" =~ ^[0-9]+$ ]] || [ "$value" -eq 0 ]; then
    echo "FATAL: derivation '${name}' (${fn}) produced '${value}', not a positive integer."
    echo "       The source it reads has moved, been renamed, or changed shape."
    echo "       Fix the derivation so it measures again. Do NOT replace it with a"
    echo "       literal — a gate that cannot measure is not a gate, it is a claim"
    echo "       that something was measured."
    exit 2
  fi
  eval "$var=\$value"
}

# expect DOC "literal text" "what it is"
#
# -F because every pattern here is prose, not a regex: one unescaped '(' would
# silently match nothing and the assertion would pass by accident.
expect() {
  local doc="$1" literal="$2" what="$3"
  if [ ! -f "$doc" ]; then
    err "${doc} is listed in this check but does not exist."
    return
  fi
  if ! grep -qF -- "$literal" "$doc"; then
    err "${doc} does not state ${what}. Expected the literal text:"
    echo "         \"${literal}\""
  fi
}

# ---------------------------------------------------------------------------
# Derivations. One function each, so the command that produces a number sits
# next to the name of the number and neither can drift from the other.
# ---------------------------------------------------------------------------

d_version_code() { grep -oE 'versionCode = [0-9]+' "$BUILD_FILE" | head -1 | grep -oE '[0-9]+'; }

# Counts @IntoSet bindings, not `registry.register` calls: ToolsModule became
# a multibinding module, so each tool is now one @Provides @IntoSet function
# instead of a constructor parameter plus a register call eighty lines apart.
# Same shape as d_providers below, which has always counted @StringKey.
d_tools() { grep -c '@IntoSet' "$CORE_MAIN/com/aura/tools/ToolsModule.kt"; }

d_providers() { grep -c '@StringKey(' "$CORE_MAIN/com/aura/providers/ProviderModule.kt"; }

d_provider_classes() {
  grep -oE '\): Provider = [A-Za-z]+\(' "$CORE_MAIN/com/aura/providers/ProviderModule.kt" | sort -u | wc -l
}

# How many prefixes are the same OpenAI-compatible class behind a different base
# URL. Both docs use this to argue the provider layer is thinner than the prefix
# count suggests, so it has to be measured rather than remembered.
d_ollama_prefixes() {
  grep -c '): Provider = OllamaCloudProvider(' "$CORE_MAIN/com/aura/providers/ProviderModule.kt"
}

d_databases() { grep -rl '@Database(' "$CORE_MAIN" | wc -l; }

# Only the `entities = [ ... ]` block. Counting every `::class` in the file
# picks up @TypeConverters and reports one entity too many.
d_entities() {
  grep -rl '@Database(' "$CORE_MAIN" | while read -r f; do
    awk '/entities *= *\[/{inb=1} inb{print} /\]/{if(inb) inb=0}' "$f"
  done | grep -oE '[A-Za-z0-9_]+::class' | wc -l
}

d_migrations() {
  grep -rhcE ': Migration\(' "$CORE_MAIN" --include=*.kt | awk '{s+=$1} END {print s+0}'
}

d_memory_db_version() {
  grep -oE 'version = [0-9]+' "$CORE_MAIN/com/aura/memory/MemoryDatabase.kt" | head -1 | grep -oE '[0-9]+'
}

d_memory_schema_max() {
  ls aura-core/schemas/com.aura.memory.MemoryDatabase/*.json 2>/dev/null \
    | sed 's#.*/##;s#\.json##' | sort -n | tail -1
}

d_backup_schema() {
  grep -oE 'const val SCHEMA_VERSION = [0-9]+' "$CORE_MAIN/com/aura/backup/AuraBackup.kt" | grep -oE '[0-9]+'
}

d_nav_destinations() { grep -c 'composable(' "$APP_MAIN/com/aura/ui/nav/NavGraph.kt"; }

d_viewmodels() { grep -r '@HiltViewModel' app/src/main --include=*.kt | wc -l; }

d_secondary_routes() {
  grep -cE '^[[:space:]]+data object [A-Za-z]+ : Route\(' "$APP_MAIN/com/aura/ui/nav/AuraBottomNavigation.kt"
}

# The bar renders `topLevelRoutes`, not every TopLevelRoute subclass —
# TopLevelRoute.Evolution exists only to key the badge map.
d_tabs() {
  sed -n '/^internal val topLevelRoutes = listOf(/,/^)/p' \
    "$APP_MAIN/com/aura/ui/nav/AuraBottomNavigation.kt" | grep -cE '^[[:space:]]+TopLevelRoute\.'
}

d_specialists() {
  grep -oE 'val ALL: List<Specialist> = listOf\([^)]*\)' "$CORE_MAIN/com/aura/agent/Specialist.kt" \
    | tr ',' '\n' | grep -c .
}

d_evolution_actions() {
  sed -n '/^enum class EvolutionAction {/,/^}/p' "$CORE_MAIN/com/aura/evolution/EvolutionAction.kt" \
    | grep -cE '^[[:space:]]+[A-Z_]+,[[:space:]]*$'
}

d_kg_node_types() {
  sed -n '/^enum class NodeType {/,/^}/p' "$CORE_MAIN/com/aura/kg/KgModels.kt" \
    | grep -oE '\b[A-Z][A-Z_]*\b' | grep -v '^NodeType$' | sort -u | wc -l
}

d_kg_edge_types() {
  sed -n '/^enum class EdgeType {/,/^}/p' "$CORE_MAIN/com/aura/kg/KgModels.kt" \
    | grep -oE '\b[A-Z][A-Z_]*\b' | grep -v '^EdgeType$' | sort -u | wc -l
}

d_trace_types() {
  sed -n '/^enum class TraceEventType {/,/^}/p' "$CORE_MAIN/com/aura/agent/runtime/AgentTraceEvent.kt" \
    | grep -cE '^[[:space:]]+[A-Z][A-Z_]*,[[:space:]]*$'
}

# Distinct TraceEventType constants production code actually hands to
# TraceSink.emit. The enum has always had more members than the loop emits, and
# quoting the enum size alone made the Diagnostics screen look like a complete
# audit trail of the run.
d_trace_emitted() {
  grep -rhoE 'TraceEventType\.[A-Z][A-Z_]*' "$CORE_MAIN" "$APP_MAIN" --include=*.kt | sort -u | wc -l
}

d_creative_modes() {
  sed -n '/^enum class CreativeMode(/,/^}/p' "$CORE_MAIN/com/aura/creative/CreativeEngine.kt" \
    | grep -cE '^[[:space:]]{4}[A-Z][A-Z_]*\($'
}

d_council_roles() {
  grep -cE '^[[:space:]]{4}[A-Z][A-Z_]*\($' "$CORE_MAIN/com/aura/creative/CouncilRoles.kt"
}

d_android_tests() {
  grep -rhcE '^[[:space:]]*@Test[[:space:]]*$' app/src/androidTest aura-core/src/androidTest \
    --include=*.kt | awk '{s+=$1} END {print s+0}'
}

# ---------------------------------------------------------------------------
# Run every derivation before asserting anything, so a broken derivation aborts
# with its own message instead of surfacing as a wall of doc-drift errors.
# ---------------------------------------------------------------------------

VERSION_NAME=$(grep -oE 'versionName = "[^"]+"' "$BUILD_FILE" | head -1 | sed 's/versionName = "//;s/"//')
if [ -z "$VERSION_NAME" ]; then
  echo "FATAL: could not read versionName from ${BUILD_FILE}."
  exit 2
fi

derive VERSION_CODE      "versionCode"                d_version_code
derive TOOLS             "registered tools"           d_tools
derive PROVIDERS         "provider prefixes"          d_providers
derive PROVIDER_CLASSES  "provider classes"           d_provider_classes
derive OLLAMA_PREFIXES   "OllamaCloudProvider prefixes" d_ollama_prefixes
derive DATABASES         "Room databases"             d_databases
derive ENTITIES          "Room entities"              d_entities
derive MIGRATIONS        "Room migrations"            d_migrations
derive MEMORY_DB_VERSION "MemoryDatabase version"     d_memory_db_version
derive MEMORY_SCHEMA_MAX "highest exported MemoryDatabase schema" d_memory_schema_max
derive BACKUP_SCHEMA     "backup SCHEMA_VERSION"      d_backup_schema
derive NAV_DESTINATIONS  "nav destinations"           d_nav_destinations
derive VIEWMODELS        "ViewModels"                 d_viewmodels
derive SECONDARY_ROUTES  "secondary routes"           d_secondary_routes
derive TABS              "bottom-nav tabs"            d_tabs
derive SPECIALISTS       "specialists"                d_specialists
derive EVOLUTION_ACTIONS "EvolutionAction types"      d_evolution_actions
derive KG_NODE_TYPES     "KG node types"              d_kg_node_types
derive KG_EDGE_TYPES     "KG edge types"              d_kg_edge_types
derive TRACE_TYPES       "TraceEventType members"     d_trace_types
derive TRACE_EMITTED     "TraceEventType members emitted" d_trace_emitted
derive CREATIVE_MODES    "creative-engine modes"      d_creative_modes
derive COUNCIL_ROLES     "creative council roles"     d_council_roles
derive ANDROID_TESTS     "instrumented test methods"  d_android_tests

# ---------------------------------------------------------------------------
# Assertions
# ---------------------------------------------------------------------------

expect "$README" "v${VERSION_NAME}"            "the build versionName v${VERSION_NAME}"
expect "$README" "versionCode ${VERSION_CODE}" "the build versionCode ${VERSION_CODE}"
expect "$ARCH"   "${VERSION_NAME} (versionCode ${VERSION_CODE})" \
                 "the build version ${VERSION_NAME} (versionCode ${VERSION_CODE})"

expect "$README" "${TOOLS} built-in tools"                   "the built-in tool count"
expect "$README" "## Tool catalog (${TOOLS} built-in)"       "the tool catalog heading count"
expect "$README" "All ${TOOLS} registered tools"             "the tool catalog preamble count"
expect "$README" "browse all ${TOOLS} registered tools"      "the Tools route tool count"
expect "$README" "${TOOLS} tool implementations"             "the project-layout tool count"
expect "$README" "ToolRegistry (${TOOLS})"                   "the ToolRegistry size in the module diagram"
expect "$ARCH"   "### Tools (${TOOLS})"                      "the tools section count"
expect "$ARCH"   "${TOOLS} registered tools"                 "the build-configuration tool count"

expect "$README" "${PROVIDERS} LLM providers"                "the provider count"
expect "$README" "## Providers (${PROVIDERS} prefixes)"      "the providers heading count"
expect "$README" "ProviderRegistry (${PROVIDERS} providers)" "the ProviderRegistry size in the module diagram"
expect "$ARCH"   "### Providers (${PROVIDERS})"              "the providers section count"
expect "$ARCH"   "${PROVIDERS} provider configurations"      "the provider-configuration count"
expect "$ARCH"   "${PROVIDER_CLASSES} provider classes"      "the distinct provider class count"
expect "$ARCH"   "${OLLAMA_PREFIXES} of the ${PROVIDERS} are" "how many prefixes share OllamaCloudProvider"

expect "$README" "## Room databases (${DATABASES})"          "the Room database count"
expect "$README" "${DATABASES} Room databases"               "the Room database count in the backup line"
expect "$ARCH"   "### Room Databases (${DATABASES})"         "the Room database count"
expect "$README" "${DATABASES} databases, ${ENTITIES} entities, ${MIGRATIONS} migrations" \
                 "the Room entity and migration counts"

expect "$README" "MemoryDatabase | v${MEMORY_DB_VERSION}"    "the MemoryDatabase version"
expect "$README" "versions 1-${MEMORY_SCHEMA_MAX}"           "the exported MemoryDatabase schema range"
expect "$ARCH"   "MemoryDB v${MEMORY_DB_VERSION}"            "the MemoryDatabase version"
expect "$ARCH"   "MemoryDatabase v${MEMORY_DB_VERSION}"      "the MemoryDatabase version in the retrieval section"

expect "$README" "schema v${BACKUP_SCHEMA}"                  "the backup schema version"
expect "$ARCH"   "Backup SCHEMA_VERSION ${BACKUP_SCHEMA}"    "the backup schema version"

expect "$README" "${NAV_DESTINATIONS} nav destinations"      "the nav destination count"
expect "$README" "${VIEWMODELS} ViewModels"                  "the ViewModel count"
expect "$README" "${TABS}-tab bottom nav"                    "the bottom-nav tab count"
expect "$README" "${SECONDARY_ROUTES} secondary routes"      "the secondary route count"

expect "$README" "## Specialists (${SPECIALISTS})"           "the specialist count"
expect "$README" "${SPECIALISTS} specialists"                "the specialist count in the feature list"
expect "$README" "${SPECIALISTS} builtin agents"             "the builtin agent count"
expect "$ARCH"   "${SPECIALISTS} builtin agents"             "the builtin agent count"

expect "$README" "${EVOLUTION_ACTIONS} action types"          "the EvolutionAction count"
expect "$ARCH"   "${EVOLUTION_ACTIONS} EvolutionAction types" "the EvolutionAction count"

expect "$README" "${KG_NODE_TYPES} node types, ${KG_EDGE_TYPES} edge types" \
                 "the knowledge-graph type counts"
expect "$README" "Room, ${KG_NODE_TYPES}+${KG_EDGE_TYPES} types" \
                 "the knowledge-graph type counts in the module diagram"

expect "$README" "${TRACE_EMITTED} of ${TRACE_TYPES} TraceSink event types" \
                 "how many TraceSink event types production code emits"

expect "$README" "${CREATIVE_MODES} creative-engine modes"   "the creative-engine mode count"
expect "$README" "${COUNCIL_ROLES}-role"                     "the creative council role count"
expect "$README" "${ANDROID_TESTS} instrumented test methods" "the instrumented test method count"

# ---------------------------------------------------------------------------

if [ "$fail" -ne 0 ]; then
  echo
  echo "Documentation drift detected. Every number above was derived from source;"
  echo "the prose is what is wrong. Fix the docs, or change the code and re-run."
  exit 1
fi

echo "OK: v${VERSION_NAME} (versionCode ${VERSION_CODE}) — README.md and architecture.md agree with source."
echo "    ${TOOLS} tools; ${PROVIDERS} provider prefixes over ${PROVIDER_CLASSES} classes (${OLLAMA_PREFIXES} share OllamaCloudProvider);"
echo "    ${DATABASES} databases, ${ENTITIES} entities, ${MIGRATIONS} migrations; MemoryDB v${MEMORY_DB_VERSION} (schemas 1-${MEMORY_SCHEMA_MAX}); backup schema ${BACKUP_SCHEMA};"
echo "    ${NAV_DESTINATIONS} nav destinations, ${VIEWMODELS} ViewModels, ${TABS} tabs, ${SECONDARY_ROUTES} secondary routes;"
echo "    ${SPECIALISTS} specialists, ${EVOLUTION_ACTIONS} evolution actions, ${KG_NODE_TYPES}+${KG_EDGE_TYPES} KG types, ${TRACE_EMITTED}/${TRACE_TYPES} trace events emitted;"
echo "    ${CREATIVE_MODES} creative modes, ${COUNCIL_ROLES} council roles, ${ANDROID_TESTS} instrumented test methods."
