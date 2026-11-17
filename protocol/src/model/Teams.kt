package org.example.kotlineditor.model

// Agent teams: a manager and members, each one an agent of the ACP registry with its own model, effort, skills and
// system prompt. The user writes to the manager, who hands tasks to the members.

/** One agent of a team: its manager or one of its members. */
data class TeamAgent(
    /** Stable, generated, never shown. */
    val id: String,
    /** Unique in the team: how the manager addresses the agent. */
    val name: String,
    /** The role of the agent, told to the manager. */
    val description: String,
    /** Which agent runs it: the agent of the ACP registry a conversation would choose. */
    val agent: AgentCommand,
    /** The settings of its session: option id to value (the model, the effort). */
    val settings: Map<String, String> = emptyMap(),
    /** The ids ([AgentSkill.id]) of the skills it is given. */
    val skills: List<String> = emptyList(),
    /** What it is told before its first task, about its work and its way of working. */
    val systemPrompt: String = "",
    /**
     * Whether it may ask the user questions (the technical choices of a specification, the designs to propose) before
     * it works, instead of going on with its own guesses. Off by default: it then reports its doubts to its manager.
     */
    val canAsk: Boolean = false,
)

/** A team: one [manager], who coordinates, and at least one of [members], who do the work. */
data class Team(
    /** Stable, generated, never shown. */
    val id: String,
    /** What the user calls it. */
    val name: String,
    /** The agent the user writes to, who coordinates. */
    val manager: TeamAgent,
    /** The agents the manager hands tasks to. */
    val members: List<TeamAgent>,
)

/** A skill of a repository of skills the server offers to install: [path] is the folder of its `SKILL.md` in [source]. */
data class CatalogSkill(
    /** The repository, `owner/name`. */
    val source: String,
    /** The folder of the skill in the repository, with `/`. */
    val path: String,
    /** Its name: the `name` of the front matter of its `SKILL.md`, else the name of its folder. */
    val name: String,
    /** What it is for: the `description` of the front matter; empty when it has none. */
    val description: String,
    /** Whether it is installed in the data directory of the server, where agents find it. */
    val installed: Boolean,
)

/** What the catalog lists: the skills of the repositories that answered, and why each of the others did not. */
data class SkillCatalogListing(
    val skills: List<CatalogSkill>,
    /** The reason a repository could not be read, by repository. */
    val failures: Map<String, String>,
)

/** A folder holding a `SKILL.md` that an agent of a team can be given. */
data class AgentSkill(
    /** The absolute path of its folder. */
    val id: String,
    /** Its name: the `name` of the front matter of its `SKILL.md`, else the name of its folder. */
    val name: String,
    /** What it is for: the `description` of the front matter; empty when it has none. */
    val description: String,
    /** Whether it comes from the project (else from the data directory of the IDE). */
    val project: Boolean,
)
