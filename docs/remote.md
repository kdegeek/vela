# The remote and gamepads

Vela is driven by an IR remote through a **Flirc USB** receiver, or by a gamepad. You don't need
a mouse.

## The remote (Flirc)

The Flirc receiver turns your remote's IR buttons into keyboard keys. You teach it once, on any
computer, and it remembers the mapping itself; then you plug it into the Mac mini.

### Mapping

| Remote button | Flirc key | What it does in Vela |
|---|---|---|
| Up, Down, Left, Right | Up, Down, Left, Right arrows | Move the focus |
| OK | Enter | Open or select |
| Back | Esc | Go back |
| Home | `XF86HomePage` (the "Home" or "WWW Home" media key) if your Flirc app offers it; otherwise **Ctrl+Alt+Home** | Back to the Vela home screen, from any app |
| Play/Pause | `XF86AudioPlay` (the Play/Pause media key) | Play or pause in the app that's showing |
| Volume up | `XF86AudioRaiseVolume` (Volume Up media key) | TV volume up |
| Volume down | `XF86AudioLowerVolume` (Volume Down media key) | TV volume down |
| Mute | `XF86AudioMute` (Mute media key) | Mute the TV |

The volume keys change the **TV's** volume (through Home Assistant or IR), not the Mac's. Mute
toggles. To send them to a soundbar instead, set `volumeDevice` in `/etc/vela/vela.toml`.
Home and the volume keys work even while an app is full screen.

### Recording the mapping

1. On any Mac, Windows or Linux computer, install the **Flirc** app from
   [flirc.tv/pages/downloads](https://flirc.tv/pages/downloads) and plug in the Flirc receiver.
2. In the Flirc app, choose **Controllers → Full Keyboard** (arrows, Enter, Esc, Home). Media
   keys are under the media/keyboard controller's media section, depending on the app version.
3. For each row in the table: click the key on the screen, then point the remote at the Flirc
   and press the button you want for it. The app confirms each one.
4. For **Home**: use the media "Home" key if there is one. If not, record **Ctrl+Alt+Home**:
   in the full keyboard, select **Ctrl** and **Alt** so they stay highlighted, then click
   **Home** and press the remote's Home button. (If your version of the Flirc app can't
   combine keys, Flirc's command-line tool `flirc_util` can record combinations; see Flirc's
   support pages.)
5. To check your work, open a text editor on the computer and press the buttons; the arrows
   should move the cursor and Enter should start a new line.
6. Unplug the Flirc and plug it into the Mac mini. Nothing needs to be set up on the box.

If a button does the wrong thing, record it again; the new recording replaces the old one. The
Flirc app's **File → Clear configuration** starts over from scratch.

## Gamepads

Xbox, PlayStation and 8BitDo controllers move around the launcher with the D-pad or left stick;
A (Cross) selects and B (Circle) goes back.

The **Guide** button (Xbox, PS or Home button):

- A **short press** goes to the Vela home screen, except while **Steam** is showing: then Steam
  keeps the short press for its own menu.
- A **long press** (hold for about half a second) always goes to the Vela home screen,
  including from Steam.
