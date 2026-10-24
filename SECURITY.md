# Security policy

BioKey is an app lock, so a security problem in it can expose exactly what people use it to
protect. Please report one privately, and give us a chance to fix it before it's made public.

## Supported versions

| Version | Supported |
|---|---|
| 1.0 (the current release on Google Play) | Yes |

Fixes go into the latest release. Please check that a problem still happens there before
reporting it.

## Reporting a vulnerability

Email **support.nbdesigns@gmail.com** with "BioKey security" in the subject. Please don't open a
public issue, pull request or discussion about it.

Include what you can of:

- What the problem is, and what someone could do with it.
- Steps to reproduce it, and how reliable they are.
- BioKey's version, your phone's model and Android version, and which way BioKey was working
  (accessibility service or compatibility mode).
- A screen recording or the diagnostic log, if they help. The log lists your locked apps by
  package name, so remove anything you'd rather not send.

You should get a reply within a week. We'll tell you whether we can reproduce it, keep you posted
on a fix, and credit you in the release notes if you'd like.

## What counts

Reports we especially want:

- A way to use a locked app without authenticating, through ordinary use of the phone.
- A way for another app to learn which apps are locked, read BioKey's settings, or switch BioKey
  off or pause it without the owner's authentication.
- A weakness in the backup file's encryption, or a way to restore a backup without its password.
- Anything that leaks data off the phone.

BioKey's README lists what an app lock running as a regular app can't defend against
([Limits](https://github.com/Peacetothemountain/BioKey#limits)): root, ADB, a modified OS, safe
mode, Recents previews, and the moments listed there when a locked app can show before it's
covered. Those are known. A way around them that goes beyond what's described there is still worth
reporting.
