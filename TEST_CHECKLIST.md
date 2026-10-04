# Device test checklist

No items below have been run yet. Record device model, Android version, build hash, steps, and result for each item before calling this release-ready.

- [ ] Wrong-PIN lockout and escalation schedule
- [ ] System clock change does not reset lockout
- [ ] Reboot persistence for attempts and lockout
- [ ] Recovery works and invalidates the old code
- [ ] IMMEDIATE, AFTER_1_MIN, and SCREEN_OFF re-lock modes
- [ ] Settings locking and protected exclusions
- [ ] Back and Home leave the locked app
- [ ] Hidden launcher mode and secret code on owner's phone
- [ ] Service-disabled warning after relaunch
- [ ] Screenshots/recents blocked by FLAG_SECURE
- [ ] Lock screen appears within 150 ms
- [ ] `aapt dump permissions <release.apk>` shows no network permission
- [ ] Verify Android 12 background activity start and covering Accessibility-overlay retry on the owner's phone
