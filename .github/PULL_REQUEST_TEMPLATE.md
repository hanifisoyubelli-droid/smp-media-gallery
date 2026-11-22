<!--
Thanks for the pull request!

A reminder of the house rules (details in CONTRIBUTING.md):
- requests to the server stay SERIAL: one WebDAV request in flight, one shared SMB session;
- 412/429/502/503 means back off, never retry harder;
- comments follow the language of the file you edited (most files are Turkish);
- no credentials, real server addresses or real user names in code, logs or screenshots.
-->

### What does this change?

<!-- One or two sentences. Link the issue it fixes, if any ("Fixes #123"). -->

### Why?

<!-- The problem or measurement behind it. Numbers are welcome. -->

### How did you test it?

- **Device / Android version:**
- **Server type:** SMB / Nextcloud
- **Library size (roughly):**
- **What I did:**

<!-- For anything performance-related: what did you measure before and after? -->

### Screenshots / recording

<!-- Required for UI changes. Redact real folder names, server addresses and user names. -->

### Checklist

- [ ] `./gradlew assembleRelease` builds without errors
- [ ] I did not add parallel requests to the server (see CONTRIBUTING.md, rule 1)
- [ ] New comments follow the language of the file I edited
- [ ] No credentials, real host names, share names or user names are included
- [ ] Docs updated if behaviour changed (README.md, docs/SELF_HOSTED.md, docs/PRIVACY.md)
