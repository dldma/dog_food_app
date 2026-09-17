# Git / GitHub setup

This project is prepared for Git version control.

## Recommended first-time setup

Open a terminal in the project root (the folder containing `app`, `settings.gradle.kts`, and `.gitignore`).

```bash
git init
git add .
git status
```

Before committing, review `git status`. Build outputs, Android Studio local settings, `local.properties`, and Windows-generated files should not appear because `.gitignore` excludes them.

Then commit:

```bash
git commit -m "Initial Android app"
git branch -M main
```

After creating an empty GitHub repository:

```bash
git remote add origin https://github.com/YOUR_ID/YOUR_REPOSITORY.git
git push -u origin main
```

## Before every commit

```bash
git status
git diff
```

Then add only the files you intended to change.

```bash
git add app/src README.md STAGE1_CHANGES.md
# or, after checking status carefully:
git add .
```

## Files intentionally ignored

- `.idea/`, `*.iml`: Android Studio local project state
- `.gradle/`, `build/`, `app/build/`: generated build/cache files
- `local.properties`: contains a machine-specific Android SDK path
- `*.apk`, `*.aab`: generated packages
- `Thumbs.db`, `Desktop.ini`: Windows Explorer-generated files
- `*.jks`, `*.keystore`, `keystore.properties`: signing keys/secrets
