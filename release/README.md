# Complemetal 0.4.1 publication kit

This directory contains reviewable GitHub and Modrinth metadata. It does not
contain credentials or generated JARs. Release binaries are created under the
ignored `build/release/` directory by `scripts/package_release.sh`.

## Publication target

- GitHub repository: <https://github.com/daniiarkg/complemetal-mc>
- Git tag: `v0.4.1+mc26.2`
- GitHub release notes: `GITHUB_RELEASE_NOTES_0.4.1.md`
- Modrinth project: <https://modrinth.com/mod/complemetal>, id `eQOVChw5`
- Modrinth version number: `0.4.1+mc26.2`
- Exact JAR SHA-256:
  `e291b3b80c702ee90fc5f42b1ef5062b3c17e2e0cf383e55e34c55929e62f554`

The prior `0.4.0+mc26.2` Modrinth version id is `aQBN08lk`. It is historical
and must not be overwritten; `0.4.1` is uploaded as a new version.

## GitHub

1. Run the complete build and field matrix from
   `docs/RELEASE_CHECKLIST_0.4.1.md`.
2. Commit the verified source, create annotated tag `v0.4.1+mc26.2`, and push
   the branch and tag without force.
3. Create a GitHub release from that tag.
4. Attach the release JAR, sources JAR, field-QA archive, and `SHA256SUMS` from
   `build/release/complemetal-0.4.1+mc26.2/`.
5. Use `GITHUB_RELEASE_NOTES_0.4.1.md` as the release body and verify every
   link and checksum before publishing.

## Modrinth

The API project template is `modrinth-project.json`; inject the long body from
`MODRINTH_DESCRIPTION.md` into its `body` field when updating project
metadata. The local environment file supplies the existing project id and API
token; neither value belongs in Git or release output.

Replace `__MODRINTH_PROJECT_ID__` in `modrinth-version.json`, then upload the
exact release JAR as multipart part `file`. The version template lists these
required project dependencies:

| Project | Modrinth ID |
| --- | --- |
| Fabric API | `P7dR8mSH` |
| Sodium | `AANobbMI` |
| Iris Shaders | `YL57xq9U` |

The project icon is `src/main/resources/assets/complemetal/icon.png`: a
256 x 256 transparent PNG, 55,844 bytes.

## Credential rule

Never commit, print, or paste a Modrinth token. Load it only from the ignored
local environment file or a release platform's secret store, and verify the
remote project/version by public API after upload.
