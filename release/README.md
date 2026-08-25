# Complemetal 0.4.0 publication kit

This directory contains reviewable metadata for GitHub and Modrinth. It does
not contain credentials or generated JARs. Release binaries are created under
the ignored `build/release/` directory by `scripts/package_release.sh`.

## GitHub

Repository: <https://github.com/daniiarkg/complemetal-mc>

1. Run the complete build and exact-JAR matrix from
   `docs/RELEASE_CHECKLIST_0.4.0.md`.
2. Commit the verified source, create annotated tag `v0.4.0+mc26.2`, and push
   the branch and tag without force.
3. Create a draft GitHub release from that tag.
4. Attach the release JAR, sources JAR and `SHA256SUMS` from
   `build/release/complemetal-0.4.0+mc26.2/`.
5. Use `GITHUB_RELEASE_NOTES_0.4.0.md` as the release body, then publish the
   draft after checking every link and checksum.

## Modrinth

Proposed slug: `complemetal`. It returned HTTP 404 from the public Modrinth API
on 2026-08-26, so no project occupied that exact slug at preparation time.
Availability must be checked again immediately before creation.

The API project template is `modrinth-project.json`; inject the long body from
`MODRINTH_DESCRIPTION.md` into its `body` field before sending it. Create the
project as a draft and retain the returned project ID. Replace
`__MODRINTH_PROJECT_ID__` in `modrinth-version.json`, then upload the exact
release JAR as multipart part `file`.

The version template lists these required Modrinth project dependencies:

| Project | Modrinth ID |
| --- | --- |
| Fabric API | `P7dR8mSH` |
| Sodium | `AANobbMI` |
| Iris Shaders | `YL57xq9U` |

The project icon is
`src/main/resources/assets/complemetal/icon.png`: 256 x 256 transparent PNG,
55,844 bytes. This is below Modrinth's 256 KiB project-icon limit.

Never commit or paste a Modrinth token. Supply it only through an ignored
local environment variable or the release platform's secret store.
