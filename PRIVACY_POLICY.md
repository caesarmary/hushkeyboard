# Privacy Policy for hushkeyboard

**Last updated: 2026-06-25**

This policy explains, in plain language, what hushkeyboard does and does not do with your data.
If anything here is unclear, you can raise a question via the project's public GitHub repository.

---

## The short version

hushkeyboard cannot send anything you type anywhere, because the app does not connect to the
internet at all. It has no servers, collects no analytics, and has no way to find out who you are,
what you type, or how you use it.

---

## What we collect

**Nothing.** hushkeyboard does not collect your name, email address, location, contacts, usage
statistics, crash reports, or any other personal information. We have no servers to send this
information to even if the app tried.

The Android app permission this keyboard requests is the one Android requires of any keyboard app,
to register as an input method. hushkeyboard does **not** request internet access, and does not
request access to your contacts, location, microphone, camera, storage, or any other device
permission beyond what a basic keyboard needs to function.

## What is stored on your device

- **Words you type often (the "learned words" feature, if you have it enabled).** To make
  suggestions better over time, hushkeyboard can remember words you use frequently. This list:
  - is stored **only on your device**, never transmitted anywhere;
  - is **encrypted** using a key generated and held inside your device's own secure hardware
    (Android's Keystore) — the key itself never leaves the device, and we never see it;
  - **excludes anything typed into a password field**, by design — password-field content is never
    learned from or suggested back to you;
  - can be **cleared at any time**, with one confirmed tap, from the app's Settings screen.
- Your keyboard's settings (e.g. whether autocorrect is on, your preferred typing delays) — stored
  locally on your device the same way any app stores its settings, never transmitted.

Nothing you type is ever written to a log file, a crash report, or any other location that could be
read back by us or anyone else. The learned-words list above is the *only* exception, and it is
encrypted and stays on your device.

## What we do not protect against

Being honest about limits is part of taking privacy seriously. hushkeyboard cannot protect you
against:

- a device that is already compromised (rooted, infected with malware, or running spyware) —
  anything with that level of access can read what's typed before it ever reaches the keyboard;
- forensic extraction tools used on a seized and unlocked device;
- other apps already installed on your device with elevated privileges (e.g. abused accessibility
  permissions);
- side-channel attacks (e.g. someone watching you type, or analyzing typing sounds);
- an app you're typing into incorrectly reporting that a field is not a password field. Android
  requires apps to tell keyboards when a field is for a password, and hushkeyboard relies on that
  signal to disable learning and suggestions — it has no way to detect a misbehaving app that lies
  about this.

No keyboard app, including hushkeyboard, can defend against these. They require protection at the
level of your device's operating system, not the apps running on it.

## Children's privacy

hushkeyboard is not directed at children and does not knowingly collect information from anyone,
children included — there is no data collection of any kind, from any user.

## Changes to this policy

If this policy ever changes, the date at the top will be updated and the change will be described
in the app's release notes. Because the app fundamentally cannot connect to the network, any future
change to this policy can only narrow or clarify what is written here — it cannot introduce new data
collection without that also being a visible, described change to the app itself.

## Contact

Questions about this policy can be raised via the project's public GitHub repository.
