# USB Internet Guard

A no-root Android firewall. It blocks the phone's own apps from using mobile data while a PC
connected by USB tethering keeps its internet.

Target: Motorola, Android 15. Works on Android 8.0 and later.

## How it works

The app starts a local VPN that routes all IPv4 and IPv6 traffic from phone apps into the app,
and drops every packet. Nothing is forwarded to a server.

USB tethering traffic from the PC is forwarded by Android's tethering stack. On stock Android,
that traffic doesn't pass through an app VPN, so the PC keeps working. **This has to be
confirmed on the phone itself** (acceptance test 3 below). If it fails, the phone's firmware
sends tethered traffic through the VPN, and a VPN-only app can't do this job.

| File | What it does |
|---|---|
| `FirewallVpnService.kt` | Sets up the VPN interface, drops packets, counts them, shows the notification |
| `MainActivity.kt` | VPN permission, notification permission, settings shortcuts, emergency disable |
| `ui/Dashboard.kt` | Status, counters, USB status, self-test, test checklist |
| `TetherDetector.kt` | Detects the USB cable and the tethering interface (`rndis0` / `ncm0`) |
| `ConnectivityProbe.kt` | "Check phone internet": tries to reach 1.1.1.1 over IPv4 and IPv6 |
| `BootReceiver.kt` | Turns the firewall back on after a reboot if it was on before |
| `GuardSettings.kt` | DataStore: on/off choice and checklist progress |

## Getting the APK

There is no local build setup on this PC, so GitHub Actions builds the APK.

1. Create an empty GitHub repository and push this folder to it.
2. Open the **Actions** tab. The **Build APK** workflow runs on every push, or you can start it by hand.
3. Open the finished run and download the **usb-internet-guard-apk** artifact (a zip containing the APK).

To build locally instead, install Android Studio and open this folder.

### Installing

With USB debugging on:

```
adb install -r usb-internet-guard-debug.apk
```

Or copy the APK to the phone and open it. Android will ask you to allow installs from that source.

Each CI run signs the APK with a new debug key. If an update fails with a signature error,
uninstall the old version first.

## Using it

1. Open the app and tap **Enable USB Only Mode**. Accept the notification and VPN prompts.
2. Connect the PC by USB and turn on **USB tethering** (use the in-app shortcut).
3. Go through the acceptance tests below.
4. Once test 3 passes, harden it: **VPN settings → gear next to USB Internet Guard → Always-on VPN**.
   Optionally also turn on **Block connections without VPN**. Then check that the PC still has internet.

**Emergency disable** stops the firewall and opens VPN settings. If "Block connections without VPN" is on,
turn it off there too, or phone apps stay offline.

## Acceptance tests

1. Open Chrome on the phone. Pages should not load.
2. Open YouTube on the phone. Videos should not load.
3. Turn on USB tethering. The PC should browse normally. **If this fails, stop: this approach doesn't work on this firmware.**
4. Download a large file on the PC. It should keep going.
5. Unplug USB. Phone apps should still be blocked.
6. Restart the phone. The firewall should come back on by itself.

The in-app **Check phone internet** button also tests whether the phone itself can reach the internet.

### If the PC has no internet but the phone shows tethering is on

First, try pinging `1.1.1.1` from the PC.

- **The ping works, but websites don't:** DNS is the problem. Set the PC's DNS server to `1.1.1.1`
  or `8.8.8.8` and try again.
- **The ping fails too:** tethered traffic is going into the VPN on this firmware. Test 3 has failed.

## Limits

- Without root, the app can't change the kernel firewall (iptables). It works only through the VPN.
- If the app stops and lockdown ("Block connections without VPN") is off, phone apps get internet back.
- Some privileged system services may not follow VPN rules.
- Apps can't turn USB tethering on. You have to turn it on in Android settings.
