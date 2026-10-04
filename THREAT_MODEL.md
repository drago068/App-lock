# Threat model

## Intended protection

Private App Lock is a local privacy barrier against casual access to selected apps while its Accessibility service is enabled and running. It observes only foreground package/class metadata from `TYPE_WINDOW_STATE_CHANGED`; the service is configured with `canRetrieveWindowContent=false`. Credential verifiers and settings are intended to be protected at rest using Android Keystore and Tink AEAD.

## Out of scope

This is not a device security boundary. It cannot guarantee protection against uninstall, disabling Accessibility, Settings/data clearing, root or ADB access, a compromised OS, physical extraction, or factory reset. It has no network permission, cloud sync, account, analytics, ads, biometrics, or remote reset.

## Implemented controls and remaining validation

The source provides PIN and pattern entry, encrypted credential/settings storage, recovery-code rotation, a shared encrypted attempt counter, three re-lock policies, hidden launcher alias plus a credential-gated secret dialer entry, and a service-disabled warning. Root/debug signals are warnings only. If the service cannot immediately foreground the credential Activity, a secure Accessibility overlay covers the protected app and offers a user-initiated retry. These controls have not been validated on the owner's physical device. In particular, Android 12 background activity launch behavior, secret-code delivery, overlay fallback, lock latency, reboot/clock-change lockout behavior, and permission output remain unverified. Complete [TEST_CHECKLIST.md](TEST_CHECKLIST.md) before relying on this app.
