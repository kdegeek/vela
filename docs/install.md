# Installing Vela on the Mac mini

This turns the 2018 Mac mini into a Vela box. Read it through once before you start.

**Installing erases the Mac's internal drive completely, including macOS.** There is no undo.
The only way back is to reinstall macOS from Apple's Internet Recovery
([How to reinstall macOS](https://support.apple.com/en-us/102655)).

## What you need

- Two USB sticks. One holds the installer (8 GB or more). The other holds your settings and the
  Wi-Fi firmware (any size). A stick you flash with the installer becomes read-only, so your files
  can't go on it.
- A USB keyboard, for the install and for one-time app sign-ins.
- An Ethernet cable to your router. The installer and the first start-up download things, and
  Wi-Fi only works once the firmware from step 2 is in place.
- The installer file `vela-installer.iso`. Get it from the project's GitHub page, under
  **Releases**: open the latest version (for example `v0.1.0`). GitHub limits each download to
  2 GB, so the installer comes in parts (`vela-installer.iso.part-00`, `-01`, …) plus a file
  called `SHA256SUMS`. Download all of them into one folder, then in **Terminal** (in that
  folder) join and check them:

  ```
  cat vela-installer.iso.part-* > vela-installer.iso
  shasum -a 256 -c SHA256SUMS
  ```

  Every line should end in `OK`. If one doesn't, download that part again.

## Steps

### 1. Update macOS

On the Mac, open **System Settings → General → Software Update** and install everything it
offers. The Mac's own firmware (including the T2 chip) is only updated through macOS updates, and
this is your last chance to get them.

### 2. Save the Wi-Fi and Bluetooth firmware

Linux can't ship Apple's Wi-Fi and Bluetooth firmware, so you copy it out of macOS first. The
t2linux project has a script for this; its instructions are on the
[Wi-Fi and Bluetooth page of the t2linux wiki](https://wiki.t2linux.org/guides/wifi-bluetooth/).

1. In macOS, open **Terminal** and run:

   ```
   curl -fsSLO https://wiki.t2linux.org/tools/firmware.sh
   bash firmware.sh
   ```

2. When it asks how to move the firmware to Linux, choose **Method 2: create a tarball of the
   firmware**. It saves `firmware.tar` in your **Downloads** folder. It may offer to install
   `python3` first; say yes.
3. Plug in the settings stick. In **Disk Utility**, erase it as **MS-DOS (FAT)** or **ExFAT**
   and name it `VELA`.
4. On the stick, make a folder called `vela-firmware` and copy `firmware.tar` into it.

### 3. Allow booting from USB

1. Shut the Mac down. Turn it on and immediately hold **Command (⌘)-R** until you see the Apple
   logo. This starts macOS Recovery
   ([Apple's instructions](https://support.apple.com/en-us/102518)).
2. Choose an admin user and enter its password if asked.
3. From the menu bar, choose **Utilities → Startup Security Utility**.
4. Under **Secure Boot**, choose **No Security**.
5. Under **External Boot**, choose **Allow booting from external or removable media**.
6. Quit and shut down.

Apple describes these settings in
[About Startup Security Utility](https://support.apple.com/en-us/102522).

### 4. Flash the installer stick

On any computer, write `vela-installer.iso` to the other stick with
[balenaEtcher](https://etcher.balena.io/): choose the file, choose the stick, and click
**Flash**. This erases the stick.

### 5. Put your settings on the VELA stick

Copy these to the top level of the VELA stick (next to the `vela-firmware` folder):

- **`vela.toml`** (needed). Start from the example,
  [`os/files/etc/vela/vela.toml.example`](../os/files/etc/vela/vela.toml.example), and save it as
  `vela.toml`. For your Jellyfin server, set:

  ```toml
  [media]
  provider = "jellyfin"

  [media.jellyfin]
  url = "http://your-jellyfin-server:8096"
  username = "your-user-name"
  ```

  The TV and Home Assistant parts can wait until step 10.
- **`secrets.json`** (optional now). Passwords and tokens never go in `vela.toml`; they go here.
  For Jellyfin, create an API key in Jellyfin (**Dashboard → API Keys**) and find your user ID
  (open your user under **Dashboard → Users**; it's the `userId=` part of the address):

  ```json
  {
    "jellyfinToken": "the API key",
    "jellyfinUserId": "your user ID"
  }
  ```

- **`authorized_keys`** (recommended). The box has no terminal, so the only way to change
  `vela.toml` or `secrets.json` later is over SSH. Put your SSH public key (for example the
  contents of `~/.ssh/id_ed25519.pub` on your computer) in a file called `authorized_keys`. You
  can then log in with `ssh root@<box address>`.

### 6. Start the installer

1. Plug both sticks, the USB keyboard and the Ethernet cable into the Mac mini.
2. Turn it on and hold **Option (⌥)** (the **Alt** key on a PC keyboard) until the boot picker
   appears.
3. Choose **EFI Boot** (the installer stick) and press Enter.

### 7. Install (this wipes the drive)

The installer runs on its own: it erases the internal drive, installs Vela, copies the firmware
and your files from the VELA stick, and restarts. Nothing asks for confirmation.

When the screen goes dark for the restart, **unplug both sticks**. If the installer starts again
anyway, turn the Mac off, unplug the sticks, and turn it on.

### 8. First start-up

You'll see the Vela logo, then the home screen. In the background, Vela downloads the apps one
after another. This can take half an hour or more; keep Ethernet connected until it's done.
Until an app is ready, its tile says **Installing** or **Not installed**.

### 9. Sign in to each app, once

Each app stays signed in after this.

- **YouTube** and **Plex**: open the app and it shows a code. Enter it on your phone
  (youtube.com/activate, plex.tv/link).
- **Steam**: Big Picture shows a QR code; scan it with the Steam app on your phone.
- **Crunchyroll** has no code sign-in on the web. Plug in the USB keyboard and sign in once.
- **Jellyfin**: the home screen's rows already use `vela.toml` and `secrets.json` from step 5.
  The Jellyfin app itself asks for your server and account once; use the USB keyboard.

### 10. Pair the TVs in Home Assistant

Home Assistant runs on the box and controls the TVs.

1. Find the box's address in your router's list of devices. On your phone, open
   `http://<box address>:8123` and create a Home Assistant account.
2. Add the TV: **Settings → Devices & services → Add integration**, then **Samsung Smart TV**
   or **Android TV Remote**. Accept the prompt on the TV.
3. Create a token: open your profile (bottom left) → **Security** → **Long-lived access tokens**
   → **Create token**. Copy it; it's shown only once.
4. Log in to the box (`ssh root@<box address>`, using the key from step 5) and:
   - add `"homeAssistantToken": "the token"` to `/var/lib/vela/secrets.json` (create the file
     if it doesn't exist, then `chown vela:vela /var/lib/vela/secrets.json` and
     `chmod 600 /var/lib/vela/secrets.json`);
   - in `/etc/vela/vela.toml`, fill in the `[homeAssistant]` section and a `[[devices]]` entry
     for each TV, as shown in the example file;
   - restart Vela's background service: `systemctl --user -M vela@ restart vela-daemon`, or
     just restart the box.

## Things to know

- **Erasing is permanent.** Going back to macOS means reinstalling it from Internet Recovery
  ([How to reinstall macOS](https://support.apple.com/en-us/102655)).
- **Crunchyroll's picture quality is capped on Linux.** Its video DRM (Widevine) only allows the
  lowest security level on Linux, so Crunchyroll often plays at 720p to 1080p. This can't be
  changed on the box.
- **A Samsung TV may need IR to turn on.** Many Samsung TVs switch their network off when they
  are off, so Home Assistant can't wake them. Vela then sends the power button through an IR
  blaster instead (`powerOnFallback` in `vela.toml`).
- **Wi-Fi needs the firmware from step 2.** If you skipped it, use Ethernet; the installer notes
  what it copied in `/var/lib/vela/install.log`.
- **The remote** is set up separately; see [remote.md](remote.md).
