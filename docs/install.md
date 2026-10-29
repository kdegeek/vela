# Installing Vela on the Mac mini

This turns the 2018 Mac mini into a Vela box. Read it through once before you start.

**Installing erases the Mac's internal drive completely, including macOS.** There is no undo.
The only way back is to reinstall macOS from Apple's Internet Recovery
([How to reinstall macOS](https://support.apple.com/en-us/102655)).

## What you need

- One USB stick of 8 GB or more, for the installer.
- A USB keyboard, for the install and for one-time app sign-ins.
- An Ethernet cable to your router. The installer and the first start-up download things,
  including the Wi-Fi firmware, so Wi-Fi only works after the first start-up.
- Your phone, on the same home network as the box, with a current browser (on an iPhone, iOS 16.2
  or later).
- Your Jellyfin server's address, username and password, if you have one.
- Your SSH public key (for example the contents of `~/.ssh/id_ed25519.pub` on your computer), or a
  GitHub account that has it. The box has no terminal, so SSH is how you change its settings later.
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

### 2. Allow booting from USB

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

### 3. Flash the installer stick

On any computer, write `vela-installer.iso` to the stick with
[balenaEtcher](https://etcher.balena.io/): choose the file, choose the stick, and click
**Flash**. This erases the stick.

### 4. Start the installer (this wipes the drive)

1. Plug the stick, the USB keyboard and the Ethernet cable into the Mac mini.
2. Turn it on and hold **Option (⌥)** (the **Alt** key on a PC keyboard) until the boot picker
   appears.
3. Choose **EFI Boot** (the installer stick) and press Enter.

The installer runs on its own: it erases the internal drive, installs Vela, and restarts. Nothing
asks for confirmation.

When the screen goes dark for the restart, **unplug the stick**. If the installer starts again
anyway, turn the Mac off, unplug the stick, and turn it on.

### 5. First start-up

You'll see the Vela logo, then **Set up Vela with your phone**: a QR code, an address such as
`http://192.168.1.42:7301/`, and a 6-digit PIN. If it says **Connect Ethernet to continue**
instead, check the cable; the screen moves on by itself once the box has an address.

In the background, Vela downloads Apple's Wi-Fi and Bluetooth firmware (a minute; Wi-Fi turns on
by itself, Bluetooth after the next restart) and then the apps one after another. This can take
half an hour or more; keep Ethernet connected until it's done. Until an app is ready, its tile
says **Installing** or **Not installed**.

### 6. Set up with your phone

1. Point your phone's camera at the QR code and open the link. The PIN is part of the code, so
   that's all. Or type the address from the TV into your phone's browser and enter the PIN.
2. **Media.** The page lists the Jellyfin servers it finds on your network. Choose yours, or type
   its address. Before you type your password, the page shows the full address it will go to:
   check that it's your server. Then sign in with your Jellyfin username and password. The box
   keeps only the sign-in token Jellyfin gives back, never your password. No Jellyfin server?
   Choose **Skip for now** and Vela shows a sample library.
3. **SSH access.** Type your GitHub username to fetch your keys from GitHub, or paste a public
   key. The page shows each key's type and fingerprint; check they're yours and add them. You can
   skip this, but then nobody can sign in to the box over SSH, and its settings can only be
   changed by installing Vela again.
4. **Finish.** Check the summary and tap **Finish**. The TV goes to the home screen within a few
   seconds, and you can close the page.

You can then log in with `ssh root@<box address>` (the address the TV showed, without `http://`
and `:7301`).

Good to know:

- The setup page is plain HTTP on your home network, so your Jellyfin password crosses your
  network once, unencrypted, on its way to the box (and from the box to Jellyfin, which is
  usually plain HTTP too). Do this on your own network, not a shared one.
- A wrong PIN 5 times puts a new PIN on the TV, and that phone waits 30 seconds before it can try
  again.
- Setup closes 2 hours after it opens. If the TV says setup timed out, restart the box to open
  it again. Later, to change the Jellyfin server or add keys, log in over SSH and run
  `vela-ctl setup`: the TV shows a new code and PIN.

### 7. Sign in to each app, once

Each app stays signed in after this.

- **YouTube** and **Plex**: open the app and it shows a code. Enter it on your phone
  (youtube.com/activate, plex.tv/link).
- **Steam**: Big Picture shows a QR code; scan it with the Steam app on your phone.
- **Crunchyroll** has no code sign-in on the web. Plug in the USB keyboard and sign in once.
- **Jellyfin**: the home screen's rows already use the server you signed in to in step 6.
  The Jellyfin app itself asks for your server and account once; use the USB keyboard.

### 8. Pair the TVs in Home Assistant

Home Assistant runs on the box and controls the TVs.

1. Find the box's address in your router's list of devices. On your phone, open
   `http://<box address>:8123` and create a Home Assistant account.
2. Add the TV: **Settings → Devices & services → Add integration**, then **Samsung Smart TV**
   or **Android TV Remote**. Accept the prompt on the TV.
3. Create a token: open your profile (bottom left) → **Security** → **Long-lived access tokens**
   → **Create token**. Copy it; it's shown only once.
4. Log in to the box (`ssh root@<box address>`, using the key you added in step 6) and:
   - add `"homeAssistantToken": "the token"` to `/var/lib/vela/secrets.json` (create the file
     if it doesn't exist, then `chown vela:vela /var/lib/vela/secrets.json` and
     `chmod 600 /var/lib/vela/secrets.json`);
   - in `/etc/vela/vela.toml`, fill in the `[homeAssistant]` section and a `[[devices]]` entry
     for each TV, as shown in the
     [example file](../os/files/etc/vela/vela.toml.example);
   - restart Vela's background service: `systemctl --user -M vela@ restart vela-daemon`, or
     just restart the box.

## Advanced: preset settings on a USB stick

Instead of phone setup, you can give the installer your settings on a second USB stick. This is
still supported, for example to reinstall a box with the same settings. If the stick has
`vela.toml`, phone setup doesn't open on first start-up.

The flashed installer stick is read-only, so this has to be another stick. On a Mac, erase it in
**Disk Utility** as **MS-DOS (FAT)** or **ExFAT** and name it `VELA` (on Windows, format it as
FAT32 or exFAT). Copy any of these to the top level of the stick, and plug it in next to the
installer stick in step 4. Unplug both when the installer restarts.

- **`vela.toml`**. Start from the example,
  [`os/files/etc/vela/vela.toml.example`](../os/files/etc/vela/vela.toml.example), and save it as
  `vela.toml`. For your Jellyfin server, set:

  ```toml
  [media]
  provider = "jellyfin"

  [media.jellyfin]
  url = "http://your-jellyfin-server:8096"
  ```

  The TV and Home Assistant parts can wait until step 8.
- **`secrets.json`** (needed for Jellyfin with a preset `vela.toml`). Passwords and tokens never
  go in `vela.toml`; they go here. With a stick, Vela signs in to Jellyfin with an API key and
  your user ID, never your password. In Jellyfin's web page, signed in as an administrator:

  1. **API key:** open **Dashboard → API Keys**, click **+**, type `Vela` as the app name and
     click **OK**. Copy the long key it lists.
  2. **User ID:** open **Dashboard → Users** and click your own user (the one whose Continue
     Watching list you want on the TV). The address in the browser now ends in
     `userId=` followed by 32 letters and digits; that is your user ID.

  ```json
  {
    "jellyfinToken": "the API key",
    "jellyfinUserId": "your user ID"
  }
  ```

  If the home screen later says "Jellyfin sign-in failed", one of these two is wrong.

- **`authorized_keys`**. Your SSH public keys, one per line, for `ssh root@<box address>`. With a
  preset `vela.toml` there is no phone setup to add them, so include this file.
- **`vela-firmware`** (a folder). Apple's Wi-Fi firmware, if the box can't download it; see
  "Wi-Fi needs Apple's firmware" below.

The installer notes what it copied in `/var/lib/vela/install.log` on the box.

## Updating and rolling back

Vela updates itself the bootc way. Every night between 3 and 4 a.m. the box checks GitHub for a
newer **stable** Vela (the `ghcr.io/kdegeek/vela:stable` image, which only changes when a new
version is released and has passed the automatic boot test). If there is one, it downloads and
stages it, but never restarts on its own: the update takes effect the next time the box restarts.
Your settings (`/etc/vela`) and everything in `/var` (apps, Home Assistant, secrets) are kept.

Over SSH (`ssh root@<box address>`):

- **See what's installed and staged:** `bootc status`.
- **Update now:** `bootc upgrade --apply`. It downloads the newest stable Vela and restarts into it.
  Without `--apply` it only stages the update for the next restart.
- **Go back to the previous version:** `bootc rollback`, then `systemctl reboot`. The version you
  were on before the last update starts again; run `bootc rollback` once more to undo that.

## Things to know

- **Erasing is permanent.** Going back to macOS means reinstalling it from Internet Recovery
  ([How to reinstall macOS](https://support.apple.com/en-us/102655)).
- **Crunchyroll's picture quality is capped on Linux.** Its video DRM (Widevine) only allows the
  lowest security level on Linux, so Crunchyroll often plays at 720p to 1080p. This can't be
  changed on the box.
- **A Samsung TV may need IR to turn on.** Many Samsung TVs switch their network off when they
  are off, so Home Assistant can't wake them. Vela then sends the power button through an IR
  blaster instead (`powerOnFallback` in `vela.toml`).
- **Wi-Fi needs Apple's firmware, which the box downloads.** Apple doesn't allow anyone to
  redistribute it, so it isn't in the installer: on first start-up the box fetches the community
  [apple-bcm-firmware](https://github.com/NoaHimesaka1873/apple-bcm-firmware) package (the same
  files t2linux's firmware script copies out of macOS) and checks it against a fixed checksum. If
  that download is ever unavailable, Wi-Fi stays off (use Ethernet), and you can supply the files
  yourself: run t2linux's [firmware script](https://wiki.t2linux.org/guides/wifi-bluetooth/) in
  macOS before installing, choose the tarball method, and put `firmware.tar` in a `vela-firmware`
  folder on a VELA stick (see "Advanced: preset settings on a USB stick").
  `journalctl -u vela-wifi-firmware` shows what happened.
- **The clock** follows the time zone the installer found from your network. If it's wrong, set it
  over SSH, for example `timedatectl set-timezone America/Chicago` (`timedatectl
  list-timezones` lists the names), then restart the box (`systemctl reboot`) so the home
  screen's clock picks it up.
- **The remote** is set up separately; see [remote.md](remote.md).
