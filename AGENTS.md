# Repository role

THIS IS THE PUBLIC DEVELOPMENT REPOSITORY.

Canonical repository: `Yrika819/FLACtify`

The private historical repository is `Yrika819/FLACtify-archive`.

Never:

- add the archive as a remote;
- import its `.git` directory;
- merge old archive history merely to recover history;
- recreate old historical tags/releases;
- push public development to the archive;
- force-push `main`;
- publish APK/AAB artifacts until the separate binary-license gate is cleared.

Before any Git mutation, coding agents must run:

```sh
git rev-parse --show-toplevel
git remote -v
git branch --show-current
cat .repo-role
git status --short
```

If the repository role and remote disagree: STOP WITHOUT EDITING.

Run `scripts/verify-repo-role.sh` before repository mutations and pushes. A failing check means this is not the canonical public-development checkout; do not proceed.
