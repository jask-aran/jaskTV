# Operator rules

Read `HANDOFF.md` sections **Resume here**, **Installed versus working tree**, **Known risks and incomplete acceptance** and **Next actions in order** before continuing development. Inspect the current source and diff; the working tree contains substantial uncommitted work.

The user ships a lot of code but is not a professional software engineer. They read prose closely and diffs shallowly. Put risk in words: what behaviour changed, what you verified and how, and what is still unproven.

Keep “ran it, output below” and “not run, expected X” apart. Quote failures exactly. If you have not read an API, flag or config key in this repository or its documentation, say you are guessing.

Before anything that cannot be undone (deleting data or branches, force push, history rewrite, migrations, writing outside the project, sending data to a network endpoint), name the exact command and what it destroys, and wait. Existing explicit user authorization persists; consult the handoff for already authorized provider inspection, public references and separate debug-app installation/testing.

Use the real code and design names. When the user describes a component, state its real name and code name once, then use those names. When a name does not exist or code and design disagree, say so and suggest one. Use plain words elsewhere.

## Working practice

- Continue in the existing Kotlin/Compose stack and WSL workspace. Preserve playback, sources, downloads, Library and sync.
- Make visible progress and install debug updates frequently. Use focused builds and checks; the user explicitly prefers faster UI iteration over excessive testing.
- Preserve other work in the shared working tree. Stage focused, understood changes; do not reset or indiscriminately commit the whole tree.
- Keep credentials, stream URLs and private provider data in ignored local storage. Never include secrets in documentation, source, logs or commits.
- Use the Windows ADB recipe in HANDOFF.md and the Conduit testing instructions when device connectivity needs repair.
- Maintain only README.md, AGENTS.md and HANDOFF.md as project Markdown documentation. Record all current work, decisions, verification and remaining risks in HANDOFF.md. Preserve reference assets separately.
- Do not spawn subagents unless the user or applicable instructions explicitly ask for delegation.
